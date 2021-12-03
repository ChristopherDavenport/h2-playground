package org.http4s.ember.h2

import cats.effect._
import cats.effect.std._
import cats.syntax.all._
import scodec.bits._
import cats.data._

private[h2] trait HpackPlatform {

  def create[F[_]: Async]: F[Hpack[F]] = ???

}