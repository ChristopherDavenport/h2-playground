package org.http4s.ember.h2


/** HTTP/2 pseudo headers */
object PseudoHeaders {
  // Request pseudo headers
  val Method = ":method"
  val Scheme = ":scheme"
  val Path = ":path"
  val Authority = ":authority"

  // Response pseudo header
  val Status = ":status"
}