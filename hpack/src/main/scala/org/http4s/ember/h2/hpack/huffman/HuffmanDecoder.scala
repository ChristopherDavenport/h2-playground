package org.http4s.ember.h2.hpack

import cats.syntax.all._

import com.twitter.hpack.Huffman

object HuffmanDecoder {

}
/*
  def decode(buf: Array[Byte]): Array[Byte] = {
    val out = new scala.collection.mutable.ArrayBuffer[Byte]
    val root: Node = buildTree
    var node = root
    var current = 0
    var bits = 0

    for {
      i <- 0 until buf.length
    } {
      val b = buf(i) & 0xFF
      current = (current << 8) | b
      bits += 8
      while (bits >=8 ){
        val c = (current >> (bits - 8)) & 0xFF
        node = node match {
          case Terminal(symbol, bits) => ???
          case Branch(_, children) => children(c)
        }
        bits = bits - node.bits
        node match {
          case Terminal(symbol, bits) => 
            if (symbol === Huffman.HUFFMAN_EOS.toByte) throw new RuntimeException("EOS Decoded")
            else 
              out.addOne(symbol)
          case Branch(bits, children) => ()
        }
      }
    }

    var loop = true
    while (bits > 0 && loop){
      val c = (current << (8 - bits)) & 0xFF
      node = node match {
        case Terminal(symbol, bits) => ???
        case Branch(bits, children) => 
          children(c)
      }
      node match {
        case Terminal(symbol, thisBits) => 
          if (thisBits <= bits) {
            bits = bits - thisBits
            out.addOne(symbol)
          } else loop = false
          
        case Branch(bits, children) =>
          loop = false
      }
    }
    val mask = (1 << bits) - 1
    if ((current & mask) != mask) throw new RuntimeException("Invalid Padding")

    out.toArray
  }


  sealed trait Node {
    // def symbol: Option[Int] = this match {
    //   case Terminal(symbol, bits) =>  Some(symbol)
    //   case Branch(bits, children) => None
    // }
    // def children: Option[Array[Node]] =  this match {
    //   case Terminal(symbol, bits) => Option.empty[Array[Node]]
    //   case Branch(bits, children) => Some(children)
    // }

    def bits: Int
  }
  case class Terminal(symbol: Byte, bits: Int) extends Node
  case class Branch(bits: Int, children: Array[Node]) extends Node
  object Branch {
    def create: Branch = Branch(8, Array.ofDim[Node](256))
  }



  private def buildTree : Branch = {
    val root = Branch.create
    for {
      i <- 0 until Huffman.CODES.length
    } {
      insert(root, i, Huffman.CODES(i), Huffman.CODE_LENGTHS(i))
    }
    root
  }

  private def insert(root: Branch, symbol: Int, code: Int, length: Byte): Unit = {
    var current: Node = root
    var l = length.toInt
    while (l > 8){
      current match {
        case Terminal(symbol, bits) => 
          throw new IllegalStateException("Invalid Huffman Code: prefix not unique")

        case Branch(_, children) => 
          l = l - 8
          val i = (code >>> length) & 0xFF
          if (children(i) == null){
            children.update(i, Branch.create)
          }
          current = children(i)
      }
    }
    val terminal = Terminal(symbol.toByte, length)
    val shift = 8 -  l
    val start = (code << shift) & 0xFF
    val end = 1 << shift
    println(s"start: $start, end: $end current: $current")
    for {
      i <- start until start + end
    } {
      current match {
        case Terminal(symbol, bits) => throw new RuntimeException("Last Node In Insert should always be branch")
        case Branch(bits, children) => children.update(i, terminal)
      }
    }
  }

}
*/