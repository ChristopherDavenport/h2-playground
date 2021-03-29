package org.http4s.ember.h2

import cats._
import cats.syntax.all._
import cats.effect._
import cats.effect.std._
import cats.effect.kernel._
import scodec.bits._
import com.twitter.hpack._
import fs2._
import fs2.io.net._
import fs2.io.net.tls._
import com.comcast.ip4s._
import javax.net.ssl.SSLEngine

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

    Test.test[IO].as(ExitCode.Success)
  }

}

object Test {

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

        _ <- socket.write(Chunk.byteVector(Frame.RawFrame.toByteVector(Frame.toRaw(Frame.GoAway(0, 1, 0, None)))))
        _ <- readAndPrint(socket, decoder)

      } yield ()
    
    }
  }
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

    Priority
    RstStream - Stream Level Error

    WindowUpdate
    PushPromise - Special Case only after Open or Half-closed(remote)

*/

// class Connection()

// class Stream[F[_]](
//   id: Int,
//   state: Ref[F, StreamState],
//   sendWindow: Ref[F, Int],
//   readWindow: Ref[F, Int],
//   bodyHolder: Ref[F, Either[Unit, Vector[ByteVector]]] // Left is Body Terminated
// ){


//   def onData()


//   // def receiveFrame(frame: Frame): F[Unit]
//   // def sendFrame(frame: Frame): F[Unit]
// }