package org.http4s.ember.h2

import org.http4s.Headers
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object Encoder {

  def encodeHeaders(tEncoder: com.twitter.hpack.Encoder, headers: List[(String, String, Boolean)]): Array[Byte] = {
    val os = new ByteArrayOutputStream(1024)
    headers.foreach{ h => 
      tEncoder.encodeHeader(os, h._1.getBytes(StandardCharsets.ISO_8859_1), h._2.getBytes(StandardCharsets.ISO_8859_1), h._3)
    }
    os.toByteArray()
  }
}