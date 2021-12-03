package org.http4s.ember.h2

import cats.effect._
import cats.effect.std._
import cats.syntax.all._
import scodec.bits._
import cats.data._
import scala.scalajs.js.JSConverters._

private[h2] trait HpackPlatform {

  def create[F[_]: Async]: F[Hpack[F]] = for {
    eLock <- Semaphore[F](1)
    dLock <- Semaphore[F](1)
    e <- Sync[F].delay(new facade.Compressor(facade.HpackOptions(4096)))
    d <- Sync[F].delay(new facade.Decompressor(facade.HpackOptions(4096)))
  } yield new Impl(eLock, e, dLock, d)


  class Impl[F[_]: Async](
    cLock: Semaphore[F],
    compressor: facade.Compressor,
    dLock: Semaphore[F],
    decompressor: facade.Decompressor
  ) extends Hpack[F]{
    def encodeHeaders(headers: NonEmptyList[(String, String, Boolean)]): F[ByteVector] = cLock.permit.use{_ =>
      Sync[F].delay{
        val a = headers.map{ case (name, value, huff) => facade.Header(name, value, huff)}
          .toList
          .toJSArray
          compressor.write(a)
        val b = compressor.read()
        scodec.bits.ByteVector.view(b)
      }
    }
    def decodeHeaders(bv: ByteVector): F[NonEmptyList[(String, String)]] = dLock.permit.use{_ =>
      Sync[F].delay{
        val uint = bv.toUint8Array
        decompressor.write(uint)
        decompressor.execute()
        val list: List[facade.Header] = decompressor.read().toArray.toList
        list.map(h => (h.name, h.value)).toNel.get
      }
    }
  }

}