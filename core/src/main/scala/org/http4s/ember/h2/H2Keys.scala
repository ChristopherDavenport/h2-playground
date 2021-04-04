package org.http4s.ember.h2

import org.typelevel.vault._
import cats.effect._

object H2Keys {
  val StreamIdentifier: Key[Int] = Key.newKey[SyncIO, Int].unsafeRunSync()
}