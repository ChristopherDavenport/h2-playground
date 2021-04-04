package org.http4s.ember.h2.hpack

object HuffmanEncoder {
}
/*
  def encode(data: Array[Byte]): Array[Byte] = 
    encode(data, 0, data.length)

  def encode(data: Array[Byte], offset: Int, length: Int): Array[Byte] = {
    val buffer = scala.collection.mutable.ArrayBuffer[Byte]()
    var current : Long = 0L
    var n : Int = 0
    for {
      i <- 0 until length
    } {
      val b = data(offset + i) & 0xFF
      val code = Huffman.CODES(b)
      val bitCount = Huffman.CODE_LENGTHS(b)
      current <<= bitCount
      current |= code
      n += bitCount
      while (n >= 8){
        n -= 8
        buffer.addOne((current >> n).toByte)
      }
    }

    if (n > 0){
      current <<= (8 - n)
      current |= (0xFF >>> n) // EOS
      buffer.addOne(current.toByte)
    }
    buffer.toArray
  }

  def getEncodedLength(data: Array[Byte]): Int = {
    var len: Long = 0L
    data.foreach{b => 
      len += Huffman.CODE_LENGTHS(b & 0xFF)
    }
    (len + 7) >> 3
  }.toInt
}
*/