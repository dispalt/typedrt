package com.goodcover.akkart.tests

import cats.tagless.autoFunctorK
import cats.{Eq, Monad}
import cats.syntax.functor._
import cats.syntax.flatMap._
import com.dispalt.taglessAkka.akkaEncoder
import com.goodcover.akkart.serialization.PersistCodec
import com.goodcover.akkart.{ActionT, BehaviorM, Ctx, KeyedCtx, MonadAction}
import com.goodcover.akkart.tests.CounterEvent.{CounterDecremented, CounterIncremented}
import com.goodcover.typedrt.data.EventTag
import io.circe.{Decoder, Encoder}

@akkaEncoder
@autoFunctorK(false)
trait Counter[F[_]] {
  def increment: F[Long]
  def incrementAndGetSeq: F[Long]
  def decrement: F[Long]
  def value: F[Long]
  def lastSeq: F[Long]
  def id: F[CounterId]
}

object Counter

final case class CounterId(value: String) extends AnyVal

sealed abstract class CounterEvent extends Product with Serializable {
  def id: String
}

object CounterEvent {
  case class CounterIncremented(id: String) extends CounterEvent
  case class CounterDecremented(id: String) extends CounterEvent
  val tag: EventTag                 = EventTag("Counter")
  val tag2: EventTag                = EventTag("Counter2")
  implicit val eq: Eq[CounterEvent] = Eq.fromUniversalEquals

  implicit def encoder: PersistCodec[CounterEvent] =
    PersistCodecCirce
      .circePersistCodec[CounterEvent](
        Encoder[(String, String)].contramap[CounterEvent] {
          case CounterIncremented(id) => "ci" -> id
          case CounterDecremented(id) => "cd" -> id
        },
        Decoder[(String, String)].emap {
          case (k, f) if k == "ci" => Right(CounterIncremented(f))
          case (k, f) if k == "cd" => Right(CounterDecremented(f))
          case f                   => Left(s"Unexpected type $f")
        }
      )
}

case class CounterState(value: Long) {

  def applyEvent(e: CounterEvent): CounterState = e match {
    case CounterIncremented(_) => CounterState(value + 1)
    case CounterDecremented(_) => CounterState(value - 1)
  }
}

final class CounterActions[F[_]](implicit F: MonadAction[F, CounterId, CounterState, CounterEvent]) extends Counter[F] {

  import F._

  override def incrementAndGetSeq: F[Long] = ctx.flatMap { c =>
    append(CounterIncremented(c.ctx.name)) >> ctx.map(_.ctx.lastSequenceNr)
  }

  override def increment: F[Long] = ctx.flatMap { c =>
    append(CounterIncremented(c.ctx.name)) >> read.map(_.value)
  }

  override def decrement: F[Long] = ctx.flatMap { c =>
    append(CounterDecremented(c.ctx.name)) >> read.map(_.value)
  }

  override def value: F[Long] = read.map(_.value)

  override def lastSeq: F[Long] = ctx.map(_.ctx.lastSequenceNr)

  override def id: F[CounterId] = key
}

object CounterActions {

  def apply[F[_]](implicit F: MonadAction[F, CounterId, CounterState, CounterEvent]): Counter[F] =
    new CounterActions[F]

  def instance[F[_]: Monad]: BehaviorM[Counter, F, CounterId, CounterState, CounterEvent] =
    BehaviorM.pure(apply, CounterState(0), _.applyEvent(_))
}
