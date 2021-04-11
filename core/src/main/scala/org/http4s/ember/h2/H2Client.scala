package org.http4s.ember.h2

import cats.effect._
import cats.effect.syntax.all._
import com.comcast.ip4s._
import fs2._
import fs2.concurrent._
import fs2.io.net._
import fs2.io.net.tls._
import cats._
import cats.syntax.all._
import scodec.bits._
import scala.concurrent.duration._
import javax.net.ssl.SSLEngine
import H2Frame.Settings.ConnectionSettings.{default => defaultSettings} 


/*
Client
Send Preface
Send Settings
Receive Settings
Send Settings Ack
Connection
  Ping - Server Send Back Ping with Ack
  Settings - Server Send Back Settings with Ack
  GoAway - Connection Level Error - Attempt to send A GoAway back and disconnect noting number for replayable streams

  WindowUpdate -  Connection Level addressed to 0, each Data Frame send needs to decrease this window by the size 
                  not including the Frame header, only the content. the number of octets that the
                  sender can transmit in addition to the existing flow-control window
  n* Streams
    Send WindowUpdate
    Receive WindowUpdate
    StreamState

    Data
    Headers
    Continuation

    Priority -- Has to outline something with connection
    RstStream - Stream Level Error

    WindowUpdate
    PushPromise - Special Case only after Open or Half-closed(remote)

*/
private[h2] class H2Client[F[_]: Async](
  sg: SocketGroup[F],
  localSettings: H2Frame.Settings.ConnectionSettings,
  tls: TLSContext[F],
  connections: Ref[F, Map[(com.comcast.ip4s.Host, com.comcast.ip4s.Port), (H2Connection[F], F[Unit])]],
  onPushPromise: (org.http4s.Request[fs2.Pure], F[org.http4s.Response[F]]) => F[Outcome[F, Throwable, Unit]]
){
  import org.http4s._

  def getOrCreate(host: com.comcast.ip4s.Host, port: com.comcast.ip4s.Port, useTLS: Boolean): F[H2Connection[F]] = 
    connections.get.map(_.get((host, port)).map(_._1)).flatMap{
      case Some(connection) => Applicative[F].pure(connection)
      case None => 
        createConnection(host, port, useTLS).flatMap(tup => 
          connections.modify{map => 
            val current = map.get((host, port))
            val newMap = current.fold(map.+(((host, port), tup)))(_ => map)
            val out = current.fold(Either.left((tup._1)))(r => Either.right((r._1, tup._2)))
            (newMap, out)
          }.flatMap{
            case Right((connection, shutdown)) => 
              // println("Using Reused Connection")
              shutdown.as(connection)
            case Left(connection) => 
              // println("Using Created Connection")
              connection.pure[F]
          }
        )
    }

  def createConnection(host: com.comcast.ip4s.Host, port: com.comcast.ip4s.Port, useTLS: Boolean): F[(H2Connection[F], F[Unit])] = {
    val r = for {
      baseSocket <- sg.client(SocketAddress(host, port))
      socket <- {
        if (useTLS){
          for {
            tlsSocket <- tls.client(baseSocket, TLSParameters(applicationProtocols = Some(List("h2", "http/1.1")),  handshakeApplicationProtocolSelector = {(t: SSLEngine, l:List[String])  => 
              l.find(_ === "h2").getOrElse("http/1.1")
            }.some), None)
            _ <- Resource.eval(tlsSocket.write(Chunk.empty))
            _ <- Resource.eval(tlsSocket.applicationProtocol)
          } yield tlsSocket
        } else Resource.pure[F, Socket[F]](baseSocket)
      }
      
        // .evalMap(s => Sync[F].delay(println(s"Protocol: $s - $host:$port")))
      ref <- Resource.eval(Concurrent[F].ref(Map[Int, H2Stream[F]]()))
      initialWriteBlock <- Resource.eval(Deferred[F, Either[Throwable, Unit]])
      stateRef <- Resource.eval(Concurrent[F].ref(H2Connection.State(defaultSettings, defaultSettings.initialWindowSize.windowSize, initialWriteBlock, localSettings.initialWindowSize.windowSize, 0, 0, false, None, None)))
      queue <- Resource.eval(cats.effect.std.Queue.unbounded[F, Chunk[H2Frame]]) // TODO revisit
      hpack <- Resource.eval(Hpack.create[F])
      settingsAck <- Resource.eval(Deferred[F, Either[Throwable, H2Frame.Settings.ConnectionSettings]])
      streamCreationLock <- Resource.eval(cats.effect.std.Semaphore[F](1))
      // data <- Resource.eval(cats.effect.std.Queue.unbounded[F, Frame.Data])
      created <- Resource.eval(cats.effect.std.Queue.unbounded[F, Int])
      closed <- Resource.eval(cats.effect.std.Queue.unbounded[F, Int])
      h2 = new H2Connection(host, port, H2Connection.ConnectionType.Client, localSettings, ref, stateRef, queue, created, closed, hpack, streamCreationLock.permit, settingsAck, ByteVector.empty, socket)
      bgRead <- h2.readLoop.compile.drain.background
      bgWrite <- h2.writeLoop.compile.drain.background
      _ <- 
          Stream.fromQueueUnterminated(closed)
            .repeat
            .evalMap{i =>
              // println(s"Removed Stream $i")
              ref.update(m => m - i)
            }.compile.drain.background
      created <-
          Stream.fromQueueUnterminated(created)
          .parEvalMap(10){i =>
              val f = if (i % 2 == 0) {
                val x = for {
                  //
                  stream <- ref.get.map(_.get(i)).map(_.get) // FOLD
                  req <- stream.getRequest
                  resp = stream.getResponse.map(
                    _.covary[F].withBodyStream(stream.readBody)
                  )
                  out <- onPushPromise(req, resp).flatMap{
                    case Outcome.Canceled() => stream.rstStream(H2Error.RefusedStream)
                    case Outcome.Errored(e) => stream.rstStream(H2Error.RefusedStream)
                    case Outcome.Succeeded(_) => Applicative[F].unit
                  }

                } yield out
                x.attempt.void
              } else Applicative[F].unit
              f
          }
            .compile
            .drain
            .onError{ case e => Sync[F].delay(println(s"Server Connection Processing Halted $e"))}
            .background

      _ <- Resource.make(socket.write(Chunk.byteVector(Preface.clientBV)))(_ => 
          socket.write(Chunk.byteVector(H2Frame.toByteVector(H2Frame.GoAway(0, 0, H2Error.NoError.value, None))))
      )
      _ <- Resource.eval(h2.outgoing.offer(Chunk.singleton(H2Frame.Settings.ConnectionSettings.toSettings(localSettings))))
      settings <- Resource.eval(h2.settingsAck.get.rethrow)
      _ <- Resource.eval(stateRef.update(s => s.copy(remoteSettings = settings, writeWindow = s.remoteSettings.initialWindowSize.windowSize) ))
    } yield h2
    r.allocated
  }


  def runHttp2Only(req: Request[F]): Resource[F, Response[F]] = {
    // Host And Port are required

    for {
      host <- Resource.eval(
        Sync[F].delay{
          req.uri.host.flatMap {
            case regname: org.http4s.Uri.RegName => regname.toHostname
            case op: org.http4s.Uri.Ipv4Address => op.address.some
            case op: org.http4s.Uri.Ipv6Address => op.address.some
          }.get
        }
      )
      port <- Resource.eval(
        Sync[F].delay{
          com.comcast.ip4s.Port.fromInt(req.uri.port.getOrElse(443)).get
        }
      )
      useTLS = req.uri.scheme.map(_.value) match {
        case Some("http") => false
        case Some("https") => true
        // How Do we Choose when to use TLS, for http/1.1 this is simple its with
        // this, but with http2, there can be arbitrary schemes
        // but also probably wrong if doing websockets over http/1.1
        case Some(_) => true
        case None => true
      }
      connection <- Resource.eval(getOrCreate(host, port, useTLS))
      // Stream Order Must Be Correct, so we must grab the global lock
      stream <- Resource.make(connection.streamCreateAndHeaders.use(_ => connection.initiateLocalStream.flatMap(stream =>
        stream.sendHeaders(PseudoHeaders.requestToHeaders(req), false).as(stream)
      )))(stream => connection.mapRef.update(m => m - stream.id))
      _ <- (
        req.body.chunks.evalMap(c => stream.sendData(c.toByteVector, false)) ++
        Stream.eval(stream.sendData(ByteVector.empty, true))
      ).compile.drain.background
      resp <- Resource.eval(stream.getResponse).map(_.covary[F].withBodyStream(stream.readBody))
    } yield resp
  }
}

object H2Client {
  def impl[F[_]: Async](
    onPushPromise: (org.http4s.Request[fs2.Pure], F[org.http4s.Response[F]]) => F[Outcome[F, Throwable, Unit]], 
    tlsContext: TLSContext[F],
    settings: H2Frame.Settings.ConnectionSettings = defaultSettings
  ): Resource[F, org.http4s.client.Client[F]] = {
    for {
      sg <- Network[F].socketGroup()
      map <- Resource.eval(Concurrent[F].ref(Map[(com.comcast.ip4s.Host, com.comcast.ip4s.Port), (H2Connection[F], F[Unit])]()))
      _ <- Stream.awakeDelay(5.seconds)
        .evalMap(_ => 
          map.get
        ).flatMap(m => Stream.emits(m.toList))
        .evalMap{
          case (t, (connection, shutdown)) => 
            connection.state.get.flatMap{s => 
              if (s.closed) map.update(m => m - t) >> shutdown else Applicative[F].unit
            }.attempt
        }
        .compile
        .drain
        .background
      h2 = new H2Client(sg, settings, tlsContext, map, onPushPromise)
    } yield org.http4s.client.Client(h2.runHttp2Only)
  }
}
