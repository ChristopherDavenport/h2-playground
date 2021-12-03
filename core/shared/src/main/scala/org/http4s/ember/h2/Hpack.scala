package org.http4s.ember.h2


import scodec.bits._
import cats.data._

private[h2] trait Hpack[F[_]]{
  def encodeHeaders(headers: NonEmptyList[(String, String, Boolean)]): F[ByteVector]
  def decodeHeaders(bv: ByteVector): F[NonEmptyList[(String, String)]]
}

private[h2] object Hpack extends HpackPlatform {


}