package org.http4s.ember.h2

import cats._
import cats.syntax.all._
import cats.effect._
import cats.effect.syntax.all._
import cats.effect.std._
import cats.effect.kernel._
import scodec.bits._
import com.twitter.hpack._
import fs2._
import fs2.concurrent._
import fs2.io.net._
import fs2.io.net.tls._
import com.comcast.ip4s._
import javax.net.ssl.SSLEngine
import scala.concurrent.duration._

object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    val maxTableSize = 4096
    val base = "test"
    val encoder = new Encoder(maxTableSize)

    
    // val encoded = HuffmanEncoder.encode(base.getBytes())
    // val bv = ByteVector()
    // val decoded = HuffmanDecoder.decode(encoded)
    // val string = new String(decoded)
    // println(bv.toBin)
    // fs2.io.N

    Test.test2[IO].as(ExitCode.Success)
  }

}

object Test {

  def client[F[_]: Async]: Resource[F, org.http4s.client.Client[F]] = {
    for {
      sg <- Network[F].socketGroup()
      tlsContext <- Resource.eval(Network[F].tlsContext.system)
      map <- Resource.eval(Concurrent[F].ref(Map[(com.comcast.ip4s.Host, com.comcast.ip4s.Port), (H2Connection[F], F[Unit])]()))
      h2 = new H2Client(sg, tlsContext, map)
    } yield org.http4s.client.Client(h2.run)
  }

  def test2[F[_]: Async] = {
    client[F].use{ c => 
      c.run(org.http4s.Request[F](org.http4s.Method.GET, uri = org.http4s.Uri.unsafeFromString("https://github.com/"))) 
        .use(_.body.compile.drain)

    }
  }
  /*

  def readAndPrint[F[_]: Async](socket: Socket[F], decoder: com.twitter.hpack.Decoder): F[Unit] = {
    Stream.eval(socket.read(16384)).flatMap{chunk => 
    Stream(chunk.map(_.toByteVector))
          .unNone
          .flatMap(bv => 
            Stream.unfold(bv)(bv => 
              Frame.RawFrame.fromByteVector(bv).flatMap{
                case (raw, leftover) => 
                  Frame.fromRaw(raw).map(f => (f, leftover))
              }
            ).covary[F]
          ).evalMap{f => 
            val respond = f match {
              case Frame.Settings(_, false, _) => socket.write(Chunk.byteVector(Frame.RawFrame.toByteVector(Frame.toRaw(Frame.Settings.Ack))))
              case Frame.Headers(id, _, _, _, bytes, _) =>
                Decoder.decodeHeaders(decoder, bytes).flatMap{ l => Sync[F].delay(println(s"Received Headers: $l"))}
              case Frame.GoAway(_, _, _, _) => 
                socket.write(Chunk.byteVector(Frame.RawFrame.toByteVector(Frame.toRaw(Frame.GoAway(0, 0, 0, None))))) >>
                Sync[F].raiseError(new Throwable("GoAway Received"))
              case _ => Sync[F].unit
            } 
            Sync[F].delay(println(s"Frame: $f")) >> respond
          }
        } .compile
          .drain

  }
  def test[F[_]](implicit F: Async[F]): F[Unit] = {
    val r = for {
      sg <- Network[F].socketGroup()
      tlsContext <- Resource.eval(Network[F].tlsContext.system)
      baseSocket <- sg.client(SocketAddress(Host.fromString("github.com").get, Port.fromInt(443).get))
      tlsSocket <- tlsContext.client(baseSocket, TLSParameters(applicationProtocols = Some(List("http/1.1", "h2")),  handshakeApplicationProtocolSelector = {(t: SSLEngine, l:List[String])  => 
        println(s"t: $t, l: $l")
        l.find(_ == "h2").getOrElse("http/1.1")
      }.some), None)//logger = {s: String => Sync[F].delay(println(s))}.some)
    } yield tlsSocket

    r.use{socket => 
      for {
        protocol <- socket.applicationProtocol // Doesn't work yet currently null
        _ <- socket.session.flatTap(s => Sync[F].delay(println(s)))
        _ <- Sync[F].delay(println(s"Protocol is $protocol"))
        encoder <- Sync[F].delay(new com.twitter.hpack.Encoder(4096))
        decoder <- Sync[F].delay(new com.twitter.hpack.Decoder(65536,4096))
        _ <- socket.write(Chunk.byteVector(hex"0x505249202a20485454502f322e300d0a0d0a534d0d0a0d0a")) // Client Preface
        frame = Frame.Settings(0, false, List(Frame.Settings.SettingsEnablePush(false))) // Settings
        _ <- socket.write(Chunk.byteVector(Frame.toByteVector(frame)))
        _ <- readAndPrint(socket, decoder)
        _ <- socket.write(Chunk.byteVector(Frame.toByteVector(Frame.Ping(0, false, None))))
        _ <- readAndPrint(socket, decoder)
        encoded <- Encoder.encodeRequest(encoder, 1)(org.http4s.Request[F](org.http4s.Method.GET, uri = org.http4s.Uri.unsafeFromString("https://github.com/")))
        bv = encoded.foldRight(ByteVector.empty){case (frame, acc) => Frame.toByteVector(frame) ++ acc}
        _ <- socket.write(Chunk.byteVector(bv))
        _ <- readAndPrint(socket, decoder)

        _ <- socket.write(Chunk.byteVector(Frame.toByteVector(Frame.GoAway(0, 1, 0, None))))
        _ <- readAndPrint(socket, decoder)

      } yield ()
    
    }
  }
  */
}

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
            case Right((connection, shutdown)) => shutdown.as(connection)
            case Left(connection) => connection.pure[F]
          }
        )
    }

  def createConnection(host: com.comcast.ip4s.Host, port: com.comcast.ip4s.Port): F[(H2Connection[F], F[Unit])] = {
    val r = for {
      baseSocket <- sg.client(SocketAddress(host, port))
      tlsSocket <- tls.client(baseSocket, TLSParameters(applicationProtocols = Some(List("http/1.1", "h2")),  handshakeApplicationProtocolSelector = {(t: SSLEngine, l:List[String])  => 
        println(s"t: $t, l: $l")
        l.find(_ == "h2").getOrElse("http/1.1")
      }.some), None)
      ref <- Resource.eval(Concurrent[F].ref(Map[Int, H2Stream[F]]()))
      stateRef <- Resource.eval(Concurrent[F].ref(H2Connection.State(Frame.Settings.ConnectionSettings.default, 1)))
      queue <- Resource.eval(cats.effect.std.Queue.unbounded[F, List[Frame]]) // TODO revisit
      hpack <- Resource.eval(Hpack.create[F])
      settingsAck <- Resource.eval(Deferred[F, Either[Throwable, Unit]])
      h2 = new H2Connection(ref, stateRef, queue, hpack, settingsAck, tlsSocket)
      bgRead <- h2.readLoop.compile.drain.background
      bgWrite <- h2.writeLoop.compile.drain.background
      _ <- Stream.awakeDelay(10.seconds).evalMap(_ => h2.outgoing.offer(Frame.Ping(0, false, None) :: Nil)).compile.drain.background
      _ <- Resource.make(tlsSocket.write(Chunk.byteVector(hex"0x505249202a20485454502f322e300d0a0d0a534d0d0a0d0a")))(_ => 
        stateRef.get.map(_.highestStreamInitiated).flatMap{i => 
          tlsSocket.write(Chunk.byteVector(Frame.toByteVector(Frame.GoAway(0, i, H2Error.NoError.value, None))))
        }
      )
      _ <- Resource.eval(h2.outgoing.offer(Frame.Settings(0, false, List.empty) :: Nil))
      _ <- Resource.eval(h2.settingsAck.get.rethrow)
      _ = println("Connection Acquired")
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
      stream <- Resource.eval(connection.initiateStream)
      _ <- Resource.eval(stream.sendHeaders(org.http4s.ember.h2.Encoder.requestToHeaders(req), false))
      _ <- (
        req.body.chunks.evalMap(c => stream.sendData(c.toByteVector, false)) ++
        Stream.eval(stream.sendData(ByteVector.empty, true))
      ).compile.drain.background
      _ <- Resource.eval(Async[F].never)
      // _ <- 
    } yield Response[F](Status.Ok)
  }
}

class H2Connection[F[_]: Concurrent](
  mapRef: Ref[F, Map[Int, H2Stream[F]]],
  state: Ref[F, H2Connection.State], // odd if client, even if server
  val outgoing: cats.effect.std.Queue[F, List[Frame]],
  hpack: Hpack[F],
  val settingsAck: Deferred[F, Either[Throwable, Unit]],
  socket: Socket[F],
){

  def initiateStream: F[H2Stream[F]] = state.modify(s => 
    (s.copy(highestStreamInitiated = s.highestStreamInitiated + 2), s.highestStreamInitiated)
  ).flatMap(initiateStreamById(_))

  def initiateStreamById(id: Int): F[H2Stream[F]] = for {
    settings <- state.get.map(_.settings)
    readBlock <- Deferred[F, Either[Throwable, Unit]]
    writeBlock <- Deferred[F, Either[Throwable, Unit]]
    refState <- SignallingRef.of[F, H2Stream.State[F]](
      H2Stream.State(StreamState.Idle, settings.initialWindowSize.windowSize, writeBlock, settings.initialWindowSize.windowSize, readBlock, List(), ByteVector.empty)
    )
  } yield new H2Stream(id, state.get.map(_.settings), refState, hpack, outgoing)


  def writeLoop: Stream[F, Nothing] = 
    (Stream.eval(outgoing.take) ++
      Stream.eval(outgoing.tryTake)
      .repeat
      .takeWhile(_.isDefined)
      .unNone
    )
      .chunkMin(1024, true)
      .debug(formatter = {c => s"Writing: $c"})
      .evalMap{chunk => 
        val bv = chunk.foldLeft(ByteVector.empty){
          case (acc, next) =>
            next.foldLeft(acc){ case (acc, frame) => acc ++ Frame.toByteVector(frame)} 
        }
        socket.write(Chunk.byteVector(bv))
      }.drain ++ writeLoop

  def readLoop: Stream[F, Nothing] = {
    Stream.eval(socket.read(16384)).flatMap{chunk => 
      Stream(chunk.map(_.toByteVector))
        .unNone
        .flatMap(bv => 
          Stream.unfold(bv)(bv => 
            Frame.RawFrame.fromByteVector(bv).flatMap{
              case (raw, leftover) => 
                Frame.fromRaw(raw).map(f => (f, leftover)) // TODO holdover bytes
            }
          ).covary[F]
        )
    }
  }.repeat
      .debug(formatter = {c => s"Reading: $c"})
      .evalTap{
        case Frame.Settings(_,false, _) => 
          outgoing.offer(Frame.Settings(0, true, List.empty) :: Nil) >>
          settingsAck.complete(Either.right(())).void
        case Frame.Settings(_, true, _) => Applicative[F].unit
        case Frame.GoAway(_, _,_,_) => Applicative[F].unit
        case Frame.Ping(_, _, _) => Applicative[F].unit

        case w@Frame.WindowUpdate(i, _) => 
          i match {
            case 0 => ???
            case otherwise => 
              mapRef.get.map(_.get(otherwise)).flatMap{
                case Some(s) => 
                  s.receiveWindowUpdate(w)
                case None => Applicative[F].unit
              }
          }
          

        case h@Frame.Headers(i, _, _, _, _, _) => 
          mapRef.get.map(_.get(i)).flatMap{
            case Some(s) => 
              s.receiveHeaders(h)
            case None => Applicative[F].unit
          }
        case d@Frame.Data(i, _, _, _) => 
          mapRef.get.map(_.get(i)).flatMap{
            case Some(s) => 
              s.receiveData(d)
            case None => Applicative[F].unit
          }
        case Frame.Continuation(_, _, _) => Applicative[F].unit
        case Frame.RstStream(_, _) => Applicative[F].unit

        case Frame.PushPromise(_, _, _, _, _) => Applicative[F].unit
        case Frame.Priority(_, _, _, _) => Applicative[F].unit
      }.drain


}

object H2Connection {
  case class State(settings: Frame.Settings.ConnectionSettings, highestStreamInitiated: Int)
  // sealed trait ConnectionType
  // object ConnectionType {
  //   case object Server extends ConnectionType
  //   case object Client extends ConnectionType
  // }

}

// Will eventually hold client/server through single interface matching that of the designed paradigm
// in StreamState
class H2Stream[F[_]: Concurrent](
  val id: Int,
  val currentConnectionSettings: F[Frame.Settings.ConnectionSettings],
  val state: SignallingRef[F, H2Stream.State[F]],
  val hpack: Hpack[F],
  val enqueue: cats.effect.std.Queue[F, List[Frame]]
){


  // TODO Check Settings to Split Headers into Headers and Continuation
  def sendHeaders(headers: List[(String, String, Boolean)], endStream: Boolean): F[Unit] = 
    state.get.flatMap{ s => 
      s.state match {
        case StreamState.Idle  => 
          hpack.encodeHeaders(headers).map(bv => 
            Frame.Headers(id, None, endStream, true, bv, None)
          ).flatMap(f => enqueue.offer(f:: Nil)) <* state.update(b => b.copy(state = if (endStream) StreamState.HalfClosedLocal else StreamState.Open))
        case _ => ???
      }
    }
    
  def sendData(bv: ByteVector, endStream: Boolean): F[Unit] = state.get.flatMap{ s => 
    s.state match {
      case StreamState.Open | StreamState.HalfClosedRemote => 
        if (bv.size.toInt <= s.writeWindow){ 
          enqueue.offer(Frame.Data(id, bv, None, endStream):: Nil) >> {
            state.update(s => 
              s.copy(
                state = {if (endStream) {
                  s.state match {
                    case StreamState.Open => StreamState.HalfClosedLocal
                    case StreamState.HalfClosedRemote => StreamState.Closed
                    case state => state // Ruh-roh
                  }
                } else s.state},
                writeWindow = {s.writeWindow - bv.size.toInt},
              )
            )
          }
        } else {
          s.writeBlock.get.rethrow >> sendData(bv, endStream)
        }  
      case _ => ???
    }

  }

  

  def receiveHeaders(headers: Frame.Headers, continuations: Frame.Continuation*): F[Unit] = state.get.flatMap{
    s => 
    s.state match {
      case StreamState.Open | StreamState.HalfClosedLocal => 
        for {
          l <- hpack.decodeHeaders(headers.headerBlock)
          others <- continuations.toList.flatTraverse(c => hpack.decodeHeaders(c.headerBlockFragment))
          newstate = if (headers.endStream) s.state match {
            case StreamState.Open => StreamState.HalfClosedRemote
            case StreamState.HalfClosedLocal => StreamState.Closed
            case s => s
          } else s.state
          headersDone = headers.endHeaders || continuations.exists(_.endHeaders)
          newReadWait <- Deferred[F, Either[Throwable, Unit]]
          block <- state.modify(s => 
            (s.copy(state = newstate, readHeaders = l ::: others ::: s.readHeaders), s.readBlock)
          )
          _ <- if (headersDone) block.complete(Right(())).void else Applicative[F].unit
        } yield ()
      case _ => ???
    }
  }

  def receiveData(data: Frame.Data): F[Unit] = state.get.flatMap{ s => 
    s.state match {
      case StreamState.Open | StreamState.HalfClosedLocal => 
        val newSize = s.readWindow - data.data.size.toInt
        val newState = if (data.endStream) s.state match {
          case StreamState.Open => StreamState.HalfClosedRemote
          case StreamState.HalfClosedLocal => StreamState.Closed
          case s => s
        } else s.state
        for {
          newReadWait <- Deferred[F, Either[Throwable, Unit]]
          oldReadWait <- state.modify(s => 
            (s.copy(state = newState, readWindow = newSize, readBlock = newReadWait, readBuffer = s.readBuffer ++ data.data), s.readBlock)
          )
          _ <- oldReadWait.complete(Right(()))
        } yield ()
    }
  }

  // Important for telling folks we can send more data
  def receiveWindowUpdate(window: Frame.WindowUpdate): F[Unit] = for {
    newWriteBlock <- Deferred[F, Either[Throwable, Unit]]
    oldWriteBlock <- state.modify(s => (s.copy(writeBlock = newWriteBlock, writeWindow = s.writeWindow + window.windowSizeIncrement), s.writeBlock))
    _ <- oldWriteBlock.complete(Right(()))
  } yield ()


}

object H2Stream {
  case class State[F[_]](state: StreamState, writeWindow: Int, writeBlock: Deferred[F, Either[Throwable, Unit]], readWindow: Int, readBlock: Deferred[F, Either[Throwable, Unit]], readHeaders: List[(String, String)],  readBuffer: ByteVector)
}