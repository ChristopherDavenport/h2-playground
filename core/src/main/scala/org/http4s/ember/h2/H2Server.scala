package org.http4s.ember.h2

import cats._
import cats.syntax.all._
import cats.effect._ 
import cats.effect.syntax.all._
import fs2._
import fs2.io.net._
import fs2.io.net.tls._
import org.http4s._
import org.http4s.implicits._
import java.nio.file.{Paths, Path}
import scala.concurrent.duration._
import javax.net.ssl.SSLEngine
import com.comcast.ip4s._
import scodec.bits._
import cats.effect.std._

object H2Server {


  def impl[F[_]: Async: Parallel](
    host: Host, 
    port: Port, 
    tlsContext: TLSContext[F], 
    httpApp: HttpApp[F], 
    localSettings: Frame.Settings.ConnectionSettings = Frame.Settings.ConnectionSettings.default.copy(
      maxConcurrentStreams = Frame.Settings.SettingsMaxConcurrentStreams(1000),
      initialWindowSize = Frame.Settings.SettingsInitialWindowSize.MAX,
      maxFrameSize = Frame.Settings.SettingsMaxFrameSize.MAX
    )
  ) = for {
    sg <- Network[F].socketGroup()
    // wd <- Resource.eval(Sync[F].delay(System.getProperty("user.dir")))
    // currentFilePath <- Resource.eval(Sync[F].delay(Paths.get(wd, "keystore.jks")))
    // tlsContext <- Resource.eval(Network[F].tlsContext.fromKeyStoreFile(currentFilePath, "changeit".toCharArray, "changeit".toCharArray))//)
    _ <- sg.server(Some(host),Some(port)).map{socket => 
      val r = for {
        tlsSocket <- tlsContext.server(socket, TLSParameters(applicationProtocols = Some(List("h2", "http/1.1")),  handshakeApplicationProtocolSelector = {(t: SSLEngine, l:List[String])  => 
          l.find(_ === "h2").getOrElse("http/1.1")
        }.some))
        _ = println("TLS Socket Acquired")
        _ <- Resource.eval(tlsSocket.write(Chunk.empty))
        _ <- Resource.eval(tlsSocket.applicationProtocol)
          .evalMap(s => Sync[F].delay(println(s"Protocol: $s")))
        address <- Resource.eval(tlsSocket.remoteAddress)
        (remotehost, remoteport) = (address.host, address.port)

        ref <- Resource.eval(Concurrent[F].ref(Map[Int, H2Stream[F]]()))
        initialWriteBlock <- Resource.eval(Deferred[F, Either[Throwable, Unit]])
        stateRef <- Resource.eval(Concurrent[F].ref(H2Connection.State(localSettings, localSettings.initialWindowSize.windowSize, initialWriteBlock, localSettings.initialWindowSize.windowSize, 0, 0, false, None, None)))
        queue <- Resource.eval(cats.effect.std.Queue.unbounded[F, Chunk[Frame]]) // TODO revisit
        hpack <- Resource.eval(Hpack.create[F])
        settingsAck <- Resource.eval(Deferred[F, Either[Throwable, Frame.Settings.ConnectionSettings]])
        streamCreationLock <- Resource.eval(cats.effect.std.Semaphore[F](1))
        // data <- Resource.eval(cats.effect.std.Queue.unbounded[F, Frame.Data])
        created <- Resource.eval(cats.effect.std.Queue.unbounded[F, Int])
        closed <- Resource.eval(cats.effect.std.Queue.unbounded[F, Int])

        h2 = new H2Connection(remotehost, remoteport, H2Connection.ConnectionType.Server, localSettings, ref, stateRef, queue, created, closed, hpack, streamCreationLock.permit, settingsAck, tlsSocket)
        _ <- Resource.eval(
          tlsSocket.read(Preface.clientBV.size.toInt).flatMap{
            case Some(s) => 
              val received = s.toByteVector
              if (received == Preface.clientBV) Applicative[F].unit
              else new Throwable("Client Preface Incorrect").raiseError
            case None => 
              new Throwable("Client Preface Incorrect").raiseError
          }
        )
          


        bgWrite <- h2.writeLoop.compile.drain.background
        _ <- Resource.eval(queue.offer(Chunk.singleton(Frame.Settings.ConnectionSettings.toSettings(localSettings))))
        bgRead <- h2.readLoop.compile.drain.background

        settings <- Resource.eval(h2.settingsAck.get.rethrow)
        _ <- 
          Stream.fromQueueUnterminated(closed)
            .map(i => 
              Stream.eval(
                (Temporal[F].sleep(10.seconds) >> ref.update(m => m - i)).timeout(15.seconds).attempt.start
              )
            ).parJoin(localSettings.maxConcurrentStreams.maxConcurrency)
            .compile
            .drain
            .background

        created <-
          Stream.fromQueueUnterminated(created)          
          .map{i =>
              val x = for {
                stream <- ref.get.map(_.get(i)).map(_.get) // FOLD
                req <- stream.getRequest.map(_.covary[F].withBodyStream(stream.readBody))
                resp <- httpApp(req)
                _ <- stream.sendHeaders(PseudoHeaders.responseToHeaders(resp), false)
                pp = resp.attributes.lookup(H2Keys.PushPromises)
                pushEnabled <- stateRef.get.map(_.remoteSettings.enablePush.isEnabled)
                streams <- (Alternative[Option].guard(pushEnabled) >> pp).fold(Applicative[F].pure(List.empty)){ l => 
                  l.traverse{req => 
                    streamCreationLock.permit.use(_ =>
                      h2.initiateLocalStream.flatMap{ stream => 
                        stream.sendPushPromise(i, PseudoHeaders.requestToHeaders(req)).as((req, stream))
                      }
                    )
                  }
                }
                // _ <- Console.make[F].println("Writing Streams Commpleted")
                responses <- streams.parTraverse{ case (req, stream) => 
                  for {
                    resp <- httpApp(req.covary[F])
                    // _ <- Console.make[F].println("Push Promise Response Completed")
                    _ <- stream.sendHeaders(PseudoHeaders.responseToHeaders(resp), false) // PP Response
                  } yield (resp.body, stream)
                }

                _ <- responses.parTraverse{ case (body, stream) => 
                  resp.body.chunks.evalMap(c => stream.sendData(c.toByteVector, false)).compile.drain >> // PP Resp Body
                    stream.sendData(ByteVector.empty, true)
                }
                _ <- resp.body.chunks.evalMap(c => stream.sendData(c.toByteVector, false)).compile.drain // Initial Resp Body
                _ <- stream.sendData(ByteVector.empty, true)
              } yield ()
              Stream.eval(x.attempt)
            
          }.parJoin(localSettings.maxConcurrentStreams.maxConcurrency)
            .compile.drain
            .onError{ case e => Sync[F].delay(println(s"Server Connection Processing Halted $e"))}
            .background


        _ <- Resource.eval(stateRef.update(s => s.copy(writeWindow = s.remoteSettings.initialWindowSize.windowSize) ))
        
        s = {
          def go: Stream[F, Unit] = Stream.awakeDelay(30.seconds).void ++ Stream.eval(tlsSocket.isOpen).ifM(go, Stream.empty)
          go
        }
        _ <- Resource.eval(s.compile.drain)

      
      } yield ()

      Stream.resource(r).handleErrorWith(e => 
        Stream.eval(Sync[F].delay(println(s"Encountered Error With Connection $e")))
      )

    }.parJoin(200)
      .compile
      .resource
      .drain
  } yield ()

}