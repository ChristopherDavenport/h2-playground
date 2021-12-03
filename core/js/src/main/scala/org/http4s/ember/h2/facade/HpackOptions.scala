package org.http4s.ember.h2.facade

import scala.scalajs.js

@js.native
private[h2] sealed trait HpackOptions extends js.Object

private[h2] object HpackOptions {
  def apply(tableSize: Int): HpackOptions =
    js.Dynamic.literal(table = js.Dynamic.literal(size = tableSize)).asInstanceOf[HpackOptions]
}
