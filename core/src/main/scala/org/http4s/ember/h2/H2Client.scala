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
class H2Client[F[_]: Async](
  sg: SocketGroup[F],
  localSettings: Frame.Settings.ConnectionSettings,
  // network: Network[F],
  tls: TLSContext[F],
  connections: Ref[F, Map[(com.comcast.ip4s.Host, com.comcast.ip4s.Port), (H2Connection[F], F[Unit])]]
){
  import org.http4s._

  def getOrCreate(host: com.comcast.ip4s.Host, port: com.comcast.ip4s.Port): F[H2Connection[F]] = 
    connections.get.map(_.get((host, port)).map(_._1)).flatMap{
      case Some(connection) => Applicative[F].pure(connection)
      case None => 
        createConnection(host, port).flatMap(tup => 
          connections.modify{map => 
            val current = map.get((host, port))
            val newMap = current.fold(map.+(((host, port), tup)))(_ => map)
            val out = current.fold(Either.left((tup._1)))(r => Either.right((r._1, tup._2)))
            (newMap, out)
          }.flatMap{
            case Right((connection, shutdown)) => 
              println("Using Reused Connection")
              shutdown.as(connection)
            case Left(connection) => 
              println("Using Created Connection")
              connection.pure[F]
          }
        )
    }

  def createConnection(host: com.comcast.ip4s.Host, port: com.comcast.ip4s.Port): F[(H2Connection[F], F[Unit])] = {
    val r = for {
      baseSocket <- sg.client(SocketAddress(host, port))
      tlsSocket <- tls.client(baseSocket, TLSParameters(applicationProtocols = Some(List("h2", "http/1.1")),  handshakeApplicationProtocolSelector = {(t: SSLEngine, l:List[String])  => 
        l.find(_ === "h2").getOrElse("http/1.1")
      }.some), None)
      _ <- Resource.eval(tlsSocket.write(Chunk.empty))
      _ <- Resource.eval(tlsSocket.applicationProtocol)
        .evalMap(s => Sync[F].delay(println(s"Protocol: $s - $host:$port")))
      ref <- Resource.eval(Concurrent[F].ref(Map[Int, H2Stream[F]]()))
      initialWriteBlock <- Resource.eval(Deferred[F, Either[Throwable, Unit]])
      stateRef <- Resource.eval(Concurrent[F].ref(H2Connection.State(Frame.Settings.ConnectionSettings.default, Frame.Settings.ConnectionSettings.default.initialWindowSize.windowSize, initialWriteBlock, localSettings.initialWindowSize.windowSize, 1)))
      queue <- Resource.eval(cats.effect.std.Queue.unbounded[F, List[Frame]]) // TODO revisit
      hpack <- Resource.eval(Hpack.create[F])
      settingsAck <- Resource.eval(Deferred[F, Either[Throwable, Unit]])
      streamCreationLock <- Resource.eval(cats.effect.std.Semaphore[F](1))
      h2 = new H2Connection(host, port, localSettings, ref, stateRef, queue, hpack, streamCreationLock.permit, settingsAck, tlsSocket)
      bgRead <- h2.readLoop.compile.drain.background
      bgWrite <- h2.writeLoop.compile.drain.background
      // _ <- Stream.awakeDelay(10.seconds).evalMap(_ => h2.outgoing.offer(Frame.Ping(0, false, None) :: Nil)).compile.drain.background
      _ <- Resource.make(tlsSocket.write(Chunk.byteVector(Preface.clientBV)))(_ => 
        stateRef.get.map(_.highestStreamInitiated).flatMap{i => 
          tlsSocket.write(Chunk.byteVector(Frame.toByteVector(Frame.GoAway(0, i, H2Error.NoError.value, None))))
        }
      )
      _ <- Resource.eval(h2.outgoing.offer(Frame.Settings.ConnectionSettings.toSettings(localSettings) :: Nil))
      _ <- Resource.eval(h2.settingsAck.get.rethrow)
      _ <- Resource.eval(stateRef.update(s => s.copy(writeWindow = s.remoteSettings.initialWindowSize.windowSize) ))
    } yield h2
    r.allocated
  }


  def run(req: Request[F]): Resource[F, Response[F]] = {
    // Host And Port are required
    val host: com.comcast.ip4s.Host = req.uri.host.flatMap {
      case regname: org.http4s.Uri.RegName => regname.toHostname
      case op: org.http4s.Uri.Ipv4Address => op.address.some
      case op: org.http4s.Uri.Ipv6Address => op.address.some
    }.get
    val port = com.comcast.ip4s.Port.fromInt(req.uri.port.getOrElse(443)).get

    for {
      connection <- Resource.eval(getOrCreate(host, port))
      // Stream Order Must Be Correct. So 
      stream <- Resource.eval(connection.streamCreateAndHeaders.use(_ => connection.initiateStream.flatMap(stream => 
        stream.sendHeaders(PseudoHeaders.requestToHeaders(req), false).as(stream)
      )))
      _ <- Resource.make(connection.mapRef.update(m => m.+(stream.id -> stream)))(_ => connection.mapRef.update(m => m - stream.id))
      _ <- (
        req.body.chunks.evalMap(c => stream.sendData(c.toByteVector, false)) ++
        Stream.eval(stream.sendData(ByteVector.empty, true))
      ).compile.drain.background
      headers <- Resource.eval(stream.getHeaders)
      resp = PseudoHeaders.headersToResponseNoBody(headers).get // TODO fix
      // contentLength = resp.contentLength
      // cutAtcontentLength = {(s: Stream[F, Byte]) => contentLength.fold(s)(l => s.take(l))}
    } yield resp.covary[F].withBodyStream(stream.readBody )
  }
}

object H2Client {
  def impl[F[_]: Async](settings: Frame.Settings.ConnectionSettings = Frame.Settings.ConnectionSettings.default): Resource[F, org.http4s.client.Client[F]] = {
    for {
      sg <- Network[F].socketGroup()
      tlsContext <- Resource.eval(Network[F].tlsContext.system) // TODO
      // sem <- Resource.eval(cats.effect.std.Semaphore[F](1))
      map <- Resource.eval(Concurrent[F].ref(Map[(com.comcast.ip4s.Host, com.comcast.ip4s.Port), (H2Connection[F], F[Unit])]()))
      h2 = new H2Client(sg, settings, tlsContext, map)
    } yield org.http4s.client.Client(h2.run)
  }
}
