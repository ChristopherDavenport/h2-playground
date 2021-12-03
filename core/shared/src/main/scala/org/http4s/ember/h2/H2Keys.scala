package org.http4s.ember.h2

import org.typelevel.vault._
import cats.effect._

object H2Keys {
  val PushPromiseInitialStreamIdentifier = Key.newKey[SyncIO, Int].unsafeRunSync()
  val StreamIdentifier: Key[Int] = Key.newKey[SyncIO, Int].unsafeRunSync()

  val PushPromises = Key.newKey[SyncIO, List[org.http4s.Request[fs2.Pure]]].unsafeRunSync()

  // Client Side Key To Try Http2-Prior-Knowledge
  // which means immediately using http without any upgrade mechanism
  // but is invalid if the receiving server does not support the
  // mechanism.
  val Http2PriorKnowledge = Key.newKey[SyncIO, Unit].unsafeRunSync()
}