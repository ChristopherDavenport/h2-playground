package org.http4s.ember.h2.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("hpack.js", "Decompressor")
private[h2] class Decompressor(options: HpackOptions) extends js.Object {
  def write(raw: js.typedarray.Uint8Array): Unit = js.native
  def execute(): Unit = js.native
  def read(): Header = js.native
}
