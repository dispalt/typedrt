package com.goodcover.typedrt.data

import scala.collection.immutable._

sealed abstract class Tagging[A] {
  def apply(e: A): Set[EventTag]
  def tags: Seq[EventTag]
}

object Tagging {

  sealed abstract class Partitioned[A] extends Tagging[A] {
    def tags: Seq[EventTag]
  }

  sealed abstract class Const[A] extends Tagging[A] {
    def tag: EventTag
    override final def apply(e: A): Set[EventTag] = Set(tag)
  }

  def const[A](tag: EventTag): Const[A] = {
    val tag0 = tag
    new Const[A] {
      override val tag: EventTag = tag0

      override val tags = Seq(tag0)
    }
  }

  def partitioned[A](numberOfPartitions: Int, tag: EventTag)(partitionKey: A => String): Partitioned[A] =
    new Partitioned[A] {
      private def tagForPartition(partition: Int) = EventTag(s"${tag.value}$partition")
      override val tags: Seq[EventTag]            = (0 to numberOfPartitions).map(tagForPartition)

      override def apply(a: A): Set[EventTag] = {
        val partition = scala.math.abs(partitionKey(a).hashCode % numberOfPartitions)
        Set(tagForPartition(partition))
      }
    }

}
