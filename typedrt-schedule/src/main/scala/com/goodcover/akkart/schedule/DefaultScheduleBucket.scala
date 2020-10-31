package com.goodcover.akkart.schedule

import java.time.{Instant, LocalDateTime, ZoneOffset, ZonedDateTime}

import cats.{Functor, Id, Monad}
import cats.effect.Effect
import cats.implicits._
import cats.kernel.Eq
import com.goodcover.akkart.msg.PersistRepr
import com.goodcover.akkart._
import com.goodcover.akkart.schedule.ScheduleEvent.{ScheduleEntryAdded, ScheduleEntryFired, ScheduleEntryRemoved}
import com.goodcover.akkart.schedule.ScheduleState.ScheduleEntry
import com.goodcover.akkart.serialization.{PersistCodec, PersistCodecRegistryBase}
import com.goodcover.akkart.serialization.PersistCodecRegistryBase
import com.goodcover.akkart.{BehaviorM, MonadActionLift}
import scalapb.{GeneratedMessage, GeneratedMessageCompanion, Message}

import scala.util.Try

object DefaultScheduleBucket {

  def apply[I[_], F[_]: Functor](
    clock: F[ZonedDateTime]
  )(
    implicit F: MonadActionLift[I, F, ScheduleBucketId, ScheduleState, ScheduleEvent]
  ): ScheduleBucket[I] =
    new DefaultScheduleBucket(clock)

  def behavior[F[_]: Monad](
    clock: F[ZonedDateTime]
  ): BehaviorM[ScheduleBucket, F, ScheduleBucketId, ScheduleState, ScheduleEvent] =
    BehaviorM(DefaultScheduleBucket(clock), _ => ScheduleState.initial, _.update(_))
}

class DefaultScheduleBucket[I[_], F[_]: Functor](
  clock: F[ZonedDateTime]
)(
  implicit F: MonadActionLift[I, F, ScheduleBucketId, ScheduleState, ScheduleEvent])
    extends ScheduleBucket[I] {
  import F._

  override def addScheduleEntry(entryId: String, correlationId: String, dueDate: LocalDateTime): I[Unit] =
    read.flatMap { state =>
      liftF(clock).flatMap { zdt =>
        val timestamp = zdt.toInstant
        val now       = zdt.toLocalDateTime
        if (state.unfired.contains(entryId) || state.fired.contains(entryId)) {
          ().pure[I]
        } else {
          append(
            ScheduleEntryAdded(
              entryId       = entryId,
              correlationId = correlationId,
              dueDate       = dueDate,
              timestamp     = timestamp
            )
          ) >>
            whenA(dueDate.isEqual(now) || dueDate.isBefore(now)) {
              append(ScheduleEntryFired(entryId, correlationId, dueDate, timestamp))
            }
        }
      }

    }

  override def fireEntry(entryId: String): I[Unit] =
    read.flatMap { state =>
      liftF(clock).map(_.toInstant).flatMap { timestamp =>
        state
          .findEntry(entryId)
          .traverse_ { entry =>
            append(
              ScheduleEntryFired(
                entryId       = entry.id,
                correlationId = entry.correlationId,
                dueDate       = entry.dueDate,
                timestamp     = timestamp
              )
            )
          }
      }
    }

  override def removeEntry(entryId: String): I[Unit] = read.flatMap { state =>
    liftF(clock).map(_.toInstant).flatMap { timestamp =>
      state
        .findEntry(entryId)
        .traverse_ { entry =>
          append(ScheduleEntryRemoved(entryId = entry.id, correlationId = entry.correlationId, timestamp = timestamp))
        }
    }
  }
}

sealed abstract class ScheduleEvent extends Product with Serializable {
  def entryId: String
  def timestamp: Instant
}

object ScheduleEvent extends ScheduleEventInstances {

  final case class ScheduleEntryAdded(
    entryId: String,
    correlationId: String,
    dueDate: LocalDateTime,
    timestamp: Instant)
      extends ScheduleEvent

  final case class ScheduleEntryFired(
    entryId: String,
    correlationId: String,
    dueDate: LocalDateTime,
    timestamp: Instant)
      extends ScheduleEvent

  final case class ScheduleEntryRemoved(entryId: String, correlationId: String, timestamp: Instant)
      extends ScheduleEvent

  implicit val eq: Eq[ScheduleEvent] = Eq.fromUniversalEquals
}

trait ScheduleEventInstances extends PersistCodecRegistryBase {
  import com.goodcover.akkart.msg
  private val prefix = registerPrefix("sp")

  final private def apply[M <: GeneratedMessage](l: Long, comp: GeneratedMessageCompanion[M]): PersistCodec[M] =
    apply[M](s"$prefix-$l", comp)

  private implicit val _1: PersistCodec[msg.ScheduleEntryAdded]   = apply(1, msg.ScheduleEntryAdded)
  private implicit val _2: PersistCodec[msg.ScheduleEntryFired]   = apply(2, msg.ScheduleEntryFired)
  private implicit val _3: PersistCodec[msg.ScheduleEntryRemoved] = apply(3, msg.ScheduleEntryRemoved)

  implicit val scheduleEvent: PersistCodec[ScheduleEvent] = new PersistCodec[ScheduleEvent] {

    override def encodeWrapper(m: ScheduleEvent): msg.PersistRepr = m match {
      case ScheduleEntryAdded(entryId, correlationId, dueDate, timestamp) =>
        PersistCodec[msg.ScheduleEntryAdded].encodeWrapper(
          msg.ScheduleEntryAdded(
            entryId,
            correlationId,
            dueDate.toInstant(ZoneOffset.UTC).toEpochMilli,
            timestamp.toEpochMilli
          )
        )
      case ScheduleEntryFired(entryId, correlationId, dueDate, timestamp) =>
        PersistCodec[msg.ScheduleEntryFired]
          .encodeWrapper(
            msg.ScheduleEntryFired(
              entryId,
              correlationId,
              dueDate.toInstant(ZoneOffset.UTC).toEpochMilli,
              timestamp.toEpochMilli
            )
          )

      case ScheduleEntryRemoved(entryId, correlationId, timestamp)        =>
        PersistCodec[msg.ScheduleEntryRemoved]
          .encodeWrapper(msg.ScheduleEntryRemoved(entryId, timestamp.toEpochMilli, correlationId))
    }

    override def decodeWrapper(m: PersistRepr): Try[ScheduleEvent] = decodeWrap(m).collect {
      case msg.ScheduleEntryAdded(entryId, correlationId, dueDate, timestamp) =>
        ScheduleEntryAdded(
          entryId,
          correlationId,
          LocalDateTime.ofInstant(Instant.ofEpochMilli(dueDate), ZoneOffset.UTC),
          Instant.ofEpochMilli(timestamp)
        )
      case msg.ScheduleEntryFired(entryId, correlationId, dueDate, timestamp) =>
        ScheduleEntryFired(
          entryId,
          correlationId,
          LocalDateTime.ofInstant(Instant.ofEpochMilli(dueDate), ZoneOffset.UTC),
          Instant.ofEpochMilli(timestamp)
        )
      case msg.ScheduleEntryRemoved(entryId, timestamp, correlationId)        =>
        ScheduleEntryRemoved(entryId, correlationId, Instant.ofEpochMilli(timestamp))
    }
  }
}

private[schedule] case class ScheduleState(unfired: Map[String, ScheduleEntry], fired: Set[String]) {

  def addEntry(entryId: String, correlationId: String, dueDate: LocalDateTime): ScheduleState =
    copy(unfired = unfired + (entryId -> ScheduleEntry(entryId, correlationId, dueDate)))

  def markEntryAsFired(entryId: String): ScheduleState                                        =
    copy(unfired = unfired - entryId, fired = fired + entryId)

  def findEntry(entryId: String): Option[ScheduleEntry]                                       =
    unfired.get(entryId)

  def update(event: ScheduleEvent): ScheduleState = event match {
    case ScheduleEntryAdded(entryId, correlationId, dueDate, _) =>
      addEntry(entryId, correlationId, dueDate)
    case e: ScheduleEntryFired                                  =>
      markEntryAsFired(e.entryId)
    case e: ScheduleEntryRemoved                                =>
      removeEntry(e.entryId)
  }

  def removeEntry(entryId: String): ScheduleState = {
    // Pretend it was never there, and subsequently it was never fired.  It's more like Undo, then fired.
    copy(unfired = unfired - entryId, fired = fired - entryId)
  }
}

private[schedule] object ScheduleState {

  def initial: ScheduleState = ScheduleState(Map.empty, Set.empty)

  case class ScheduleEntry(id: String, correlationId: String, dueDate: LocalDateTime)

}
