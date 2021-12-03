package org.http4s.ember.h2.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("hpack.js", "Compressor")
private[h2] class Compressor(options: HpackOptions) extends js.Object {
  def write(headers: js.Array[Header]): Unit = js.native
  def read(): js.typedarray.Uint8Array = js.native
}
