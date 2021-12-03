package org.http4s.ember.h2

import scodec.bits._
// Preface must always be sent an happens before anything else.
private[h2] object Preface {
  val client = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
  // This sequence MUST be followed by a SETTINGS frame
  val clientBV = ByteVector(client.getBytes)
  
  // The server connection preface consists of a potentially empty
  // SETTINGS frame (Section 6.5) that MUST be the first frame the server
  // sends in the HTTP/2 connection.
}