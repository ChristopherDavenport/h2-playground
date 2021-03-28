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
  def test[F[_]](implicit F: Async[F]): F[Unit] = {
    val r = for {
      sg <- Network[F].socketGroup()
      tlsContext <- Resource.eval(Network[F].tlsContext.system)
      baseSocket <- sg.client(SocketAddress(Host.fromString("github.com").get, Port.fromInt(443).get))
      tlsSocket <- tlsContext.client(baseSocket, TLSParameters(applicationProtocols = Some(List("http/1.1", "h2"))), None)//logger = {s: String => Sync[F].delay(println(s))}.some)
    } yield tlsSocket

    r.use{socket => 
      for {
        protocol <- socket.applicationProtocol // Doesn't work yet currently null
        _ <- Sync[F].delay(println(s"Protocol is $protocol"))
        _ <- socket.write(Chunk.byteVector(hex"0x505249202a20485454502f322e300d0a0d0a534d0d0a0d0a"))
        frame = Frame.Settings(0, false, List(Frame.Settings.SettingsEnablePush(false)))
        bv = Frame.RawFrame.toByteVector(Frame.toRaw(frame))
        _ <- socket.write(Chunk.byteVector(bv))
        chunk <- socket.read(16384)
        _ <- Stream(chunk.map(_.toByteVector))
          .unNone
          .flatMap(bv => 
            Stream.unfold(bv)(bv => 
              Frame.RawFrame.fromByteVector(bv).flatMap{
                case (raw, leftover) => 
                  Frame.fromRaw(raw).map(f => (f, leftover))
              }
            ).covary[F]
          ).evalMap(f => Sync[F].delay(println(s"Frame: $f")))
          .compile
          .drain

      } yield ()
    
    }
  }
}