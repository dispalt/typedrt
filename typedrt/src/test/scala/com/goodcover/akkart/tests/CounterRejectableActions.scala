package com.goodcover.akkart.tests

import cats.Monad
import cats.data.EitherT
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.goodcover.akkart.{ActionT, BehaviorM, EitherKT, KeyedCtx, MonadAction, MonadActionReject}
import com.goodcover.akkart.tests.CounterEvent.{CounterDecremented, CounterIncremented}

final class CounterRejectableActions[F[_]](
  implicit F: MonadActionReject[F, CounterId, CounterState, CounterEvent, Throwable])
    extends Counter[F] {

  import F._

  override def incrementAndGetSeq: F[Long] = ctx.flatMap { c =>
    append(CounterIncremented(c.key.value)) >> ctx.map(_.ctx.lastSequenceNr)
  }

  override def increment: F[Long] = ctx.flatMap { c =>
    append(CounterIncremented(c.key.value)) >> read.map(_.value)
  }

  override def decrement: F[Long] = for {
    c      <- ctx
    state  <- read
    _      <-
      if (state.value <= 0) reject[Unit](new IllegalStateException("Not allowed to go below 0"))
      else pure(())
    result <- append(CounterDecremented(c.key.value)) >> read.map(_.value)

  } yield result

  override def value: F[Long] = read.map(_.value)

  override def lastSeq: F[Long] = ctx.map(_.ctx.lastSequenceNr)

  override def id: F[CounterId] = key
}

object CounterRejectableActions {

  def apply[F[_]: MonadActionReject[?[_], CounterId, CounterState, CounterEvent, Throwable]]: Counter[F] =
    new CounterRejectableActions[F]

  def behavior[F[_]: Monad]: BehaviorM[EitherKT[Counter, Throwable, ?[_]], F, CounterId, CounterState, CounterEvent] =
    BehaviorM.rejectable(apply, _ => CounterState(0), _.applyEvent(_))
}
