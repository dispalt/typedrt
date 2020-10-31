package com.goodcover.fxn

import java.time._

import cats.Apply

trait Clock[F[_]] {
  def zone: F[ZoneId]
  def goodcoverZone: F[ZoneId]
  def instant: F[Instant]

  def zonedDateTime(implicit F: Apply[F]): F[ZonedDateTime] =
    F.map2(instant, zone)(ZonedDateTime.ofInstant)

  def offsetDateTime(implicit F: Apply[F]): F[OffsetDateTime] =
    F.map2(instant, zone)(OffsetDateTime.ofInstant)

  def localDateTime(implicit F: Apply[F]): F[LocalDateTime] =
    F.map2(instant, zone)(LocalDateTime.ofInstant)

  def gcZonedDateTime(implicit F: Apply[F]): F[ZonedDateTime] =
    F.map2(instant, goodcoverZone)(ZonedDateTime.ofInstant)
}

object Clock {
  val goodcoverZone: ZoneId = ZoneId.of("America/Los_Angeles")
  val reportZone: ZoneId    = ZoneOffset.UTC
}
