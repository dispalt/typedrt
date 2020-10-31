package com.goodcover.fxn

import java.time.{Instant, ZoneId}

import cats.effect.Sync
import monix.eval.Task

class JavaTimeClock[F[_]: Sync](underlying: java.time.Clock) extends Clock[F] {
  override def zone: F[ZoneId] = Sync[F].delay(underlying.getZone)

  override def goodcoverZone: F[ZoneId] = Sync[F].delay(JavaTimeClock.goodcoverZone)

  override def instant: F[Instant] = Sync[F].delay(underlying.instant())
}

object JavaTimeClock {

  val goodcoverZone: ZoneId = Clock.goodcoverZone

  def apply[F[_]: Sync](underlying: java.time.Clock): Clock[F] =
    new JavaTimeClock(underlying)
  def systemDefaultF[F[_]: Sync]: Clock[F]                     = apply(java.time.Clock.systemDefaultZone())
  def systemUTCF[F[_]: Sync]: Clock[F]                         = apply(java.time.Clock.systemUTC())
  def systemUTC: Clock[Task]                                   = systemUTCF[Task]
}
