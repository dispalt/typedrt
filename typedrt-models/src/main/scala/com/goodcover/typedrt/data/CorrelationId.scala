package com.goodcover.typedrt.data

import com.goodcover.typedrt.data.Correlation.CorrelationId

object Correlation {
  type CorrelationId     = String
  type Correlation[C[_]] = (C[_] => CorrelationId)

  def apply[C[_]](f: C[_] => String): Correlation[C] = f
}

object CorrelationId {

  def composite(
    separator: String,
    firstComponent: String,
    secondComponent: String,
    otherComponents: String*
  ): CorrelationId = {
    val replacement = s"\\$separator"
    (firstComponent +: secondComponent +: otherComponents)
      .map(_.replace(separator, replacement))
      .mkString(separator)
  }
}
