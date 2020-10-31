package com.goodcover.akkart.schedule

import java.time.LocalDateTime
import java.util.UUID

import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.effect.Effect
import cats.implicits._
import com.goodcover.fxn._
import com.goodcover.akkart.read.{CommittableEventJournalQuery, JournalEntry}
import com.goodcover.fxn.Clock
import com.goodcover.typedrt.data.{Committable, ConsumerId, EventTag}
import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.duration.FiniteDuration

class DefaultScheduling[F[_]: Effect](
  clock: Clock[F],
  buckets: ScheduleBucketId => ScheduleBucket[F],
  bucketLength: FiniteDuration)
    extends Scheduling[F] {

  override def addScheduleEntry(
    scheduleName: String,
    entryId: String,
    correlationId: String,
    dueDate: LocalDateTime
  ): F[Unit] =
    for {
      zone <- clock.zone
      scheduleBucket = dueDate.atZone(zone).toEpochSecond / bucketLength.toSeconds
      _ <- buckets(ScheduleBucketId(scheduleName, scheduleBucket.toString))
        .addScheduleEntry(entryId, correlationId, dueDate)
    } yield ()

  override def removeScheduleEntry(scheduleName: String, entryId: String, dueDate: LocalDateTime): F[Unit] =
    for {
      zone <- clock.zone
      scheduleBucket = dueDate.atZone(zone).toEpochSecond / bucketLength.toSeconds
      _ <- buckets(ScheduleBucketId(scheduleName, scheduleBucket.toString)).removeEntry(entryId)
    } yield ()
}

private[schedule] class DefaultSchedule[F[_]: Effect](
  clock: Clock[F],
  buckets: ScheduleBucketId => ScheduleBucket[F],
  bucketLength: FiniteDuration,
  aggregateJournal: CommittableEventJournalQuery[F, UUID, ScheduleBucketId, ScheduleEvent],
  eventTag: EventTag)
    extends DefaultScheduling[F](clock, buckets, bucketLength)
    with Schedule[F] {

  override def committableScheduleEvents(
    scheduleName: String,
    consumerId: ConsumerId
  ): Source[Committable[F, JournalEntry[UUID, ScheduleBucketId, ScheduleEvent]], NotUsed] =
    aggregateJournal
      .eventsByTag(eventTag, ConsumerId(scheduleName + consumerId.value))
      .flatMapConcat {
        case m if m.value.event.key.scheduleName == scheduleName =>
          Source.single(m)
        case other                                               =>
          Source
            .future(other.commit.unsafeToFuture())
            .flatMapConcat(_ => Source.empty[Committable[F, JournalEntry[UUID, ScheduleBucketId, ScheduleEvent]]])
      }

}
