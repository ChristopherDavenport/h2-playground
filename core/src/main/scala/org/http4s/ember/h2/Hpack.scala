package org.http4s.ember.h2

import cats.effect._
import cats.effect.std._
import cats.syntax.all._
import scodec.bits._
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import com.twitter.hpack.HeaderListener
import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer

import cats.data._
private[h2] trait Hpack[F[_]]{
  def encodeHeaders(headers: NonEmptyList[(String, String, Boolean)]): F[ByteVector]
  def decodeHeaders(bv: ByteVector): F[NonEmptyList[(String, String)]]
}

private[h2] object Hpack {

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
    def encodeHeaders(headers: NonEmptyList[(String, String, Boolean)]): F[ByteVector] = 
      encodeLock.permit.use(_ => Hpack.encodeHeaders[F](tEncoder, headers.toList))
    def decodeHeaders(bv: ByteVector): F[NonEmptyList[(String, String)]] = 
      decodeLock.permit.use(_ => Hpack.decodeHeaders[F](tDecoder,bv))

  }

  def decodeHeaders[F[_]: Sync](tDecoder: com.twitter.hpack.Decoder, bv: ByteVector): F[NonEmptyList[(String, String)]] = Sync[F].delay{
    var buffer = new ListBuffer[(String, String)]
    val is = new ByteArrayInputStream(bv.toArray)
    val listener = new HeaderListener{
      def addHeader(name: Array[Byte], value: Array[Byte], sensitive: Boolean): Unit = {
        buffer.addOne(
          new String(name, StandardCharsets.ISO_8859_1) -> new String(value, StandardCharsets.ISO_8859_1)
        )
      }
    }

    tDecoder.decode(is, listener)
    tDecoder.endHeaderBlock()

    val decoded = buffer.toList
    // println(s"Decoded: $decoded")
    NonEmptyList.fromList(decoded).toRight(new Throwable("Header List Was Empty"))
  }.rethrow

  def encodeHeaders[F[_]: Sync](tEncoder: com.twitter.hpack.Encoder, headers: List[(String, String, Boolean)]): F[ByteVector] = Sync[F].delay{
    val os = new ByteArrayOutputStream(1024)
    headers.foreach{ h => 
      tEncoder.encodeHeader(os, h._1.getBytes(StandardCharsets.ISO_8859_1), h._2.getBytes(StandardCharsets.ISO_8859_1), h._3)
    }
    ByteVector.view(os.toByteArray())
  }
}