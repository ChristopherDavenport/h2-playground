package org.http4s.ember.h2.hpack

import cats._
import cats.syntax.all._
import cats.effect._
import scala.collection.immutable.ArraySeq

case class DynamicTable[F[_]: Functor](headers: Ref[F, ArraySeq[(String, String)]], maxSize: Int){
  def underlying: F[List[(String, String)]] = headers.get.map(_.toList)

  def getByIndex(i: Int): F[(String, String)] = headers.get.map(_(i))


  def addAll(incoming: List[(String, String)]): F[Unit] = {
    headers.update{ current => 
      if (current.size + incoming.size > maxSize) {
        if (incoming.size > maxSize) ArraySeq.empty
        else {
          val retained = maxSize - incoming.size
          current.take(retained).prependedAll(incoming)
        }
        
      } else {
        current.prependedAll(incoming)
      }
    }
  }
}