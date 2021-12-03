package org.http4s.ember.h2.facade

import scala.scalajs.js

@js.native
private[h2] sealed trait Header extends js.Object {
  def name: String = js.native
  def value: String = js.native
}

private[h2] object Header {
  def apply(name: String, value: String, huffman: Boolean): Header =
    js.Dynamic.literal(name = name, value = value, huffman = huffman).asInstanceOf[Header]
}
