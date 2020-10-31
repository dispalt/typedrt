package com.goodcover.akkart.testkit

import java.time.temporal.TemporalAmount
import java.time.{Instant, ZoneId, ZonedDateTime}

import cats.mtl.Stateful
import com.goodcover.fxn.{Clock, JavaTimeClock}
import monocle.Lens

class StateClock[F[_]: Stateful[?[_], S], S](zoneId: ZoneId, S: Lens[S, Instant]) extends Clock[F] {

  private val F                    = S.transformMonadState(Stateful[F, S])
  override def zone: F[ZoneId]     = F.monad.pure(zoneId)
  override def instant: F[Instant] = F.get

  override def goodcoverZone: F[ZoneId] = F.monad.pure(JavaTimeClock.goodcoverZone)

  def tick(temporalAmount: TemporalAmount): F[Unit] =
    F.modify(_.plus(temporalAmount))

  def tick(zdt: ZonedDateTime => ZonedDateTime): F[Instant] = {
    F.monad.flatMap(F.modify { instant =>
      zdt(ZonedDateTime.ofInstant(instant, zoneId)).toInstant
    })(_ => instant)
  }
}

object StateClock {

  def apply[F[_], S](zoneId: ZoneId, S: Lens[S, Instant])(implicit F0: Stateful[F, S]): StateClock[F, S] =
    new StateClock[F, S](zoneId, S)
}
