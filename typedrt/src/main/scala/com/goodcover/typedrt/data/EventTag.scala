package com.goodcover.typedrt.data

import cats.kernel.Order

final case class EventTag(value: String) extends AnyVal

object EventTag {
  implicit val orderInstance: Order[EventTag] = Order.fromOrdering(Ordering[String].on(_.value))
}
