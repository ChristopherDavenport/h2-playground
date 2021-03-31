package org.http4s.ember.h2

import cats.effect._
import cats.effect.std._
import cats.syntax.all._
import scodec.bits._

trait Hpack[F[_]]{
  def encodeHeaders(headers: List[(String, String, Boolean)]): F[ByteVector]
  def decodeHeaders(bv: ByteVector): F[List[(String, String)]]
}

object Hpack {

  def create[F[_]: Async]: F[Hpack[F]] = for {
    eLock <- Semaphore[F](1)
    dLock <- Semaphore[F](1)
    e <- Sync[F].delay(new com.twitter.hpack.Encoder(4096))
    d <- Sync[F].delay(new com.twitter.hpack.Decoder(65536,4096))
  } yield new Impl(eLock, e, dLock, d)

  private class Impl[F[_]: Async](
    encodeLock: Semaphore[F],
    tEncoder: com.twitter.hpack.Encoder,
    decodeLock: Semaphore[F],
    tDecoder: com.twitter.hpack.Decoder
  ) extends Hpack[F]{
    def encodeHeaders(headers: List[(String, String, Boolean)]): F[ByteVector] = 
      encodeLock.permit.use(_ => Encoder.encodeHeaders[F](tEncoder, headers))
    def decodeHeaders(bv: ByteVector): F[List[(String, String)]] = 
      decodeLock.permit.use(_ => Decoder.decodeHeaders[F](tDecoder,bv))

  }
}