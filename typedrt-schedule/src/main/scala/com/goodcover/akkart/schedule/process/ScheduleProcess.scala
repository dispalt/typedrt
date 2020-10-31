package com.goodcover.akkart.schedule.process

import java.time.temporal.ChronoUnit
import java.time.{Clock => _, _}

import cats.{~>, Monad}
import cats.implicits._
import com.goodcover.akkart.schedule.ScheduleEvent.{ScheduleEntryAdded, ScheduleEntryFired, ScheduleEntryRemoved}
import com.goodcover.akkart.schedule.{ScheduleBucket, ScheduleBucketId, ScheduleEntryRepository}
import com.goodcover.akkart.read.{EntityEvent, JournalEntry}
import com.goodcover.typedrt.data.{ConsumerId, EventTag, KeyValueStore, TagConsumer}

import scala.concurrent.duration.FiniteDuration

object ScheduleProcess {

  def apply[F[_]: Monad](
    journal: ScheduleEventJournal[F],
    dayZero: LocalDate,
    consumerId: ConsumerId,
    offsetStore: KeyValueStore[F, TagConsumer, LocalDateTime],
    eventualConsistencyDelay: FiniteDuration,
    repository: ScheduleEntryRepository[F],
    buckets: ScheduleBucketId => ScheduleBucket[F],
    clock: F[LocalDateTime],
    parallelism: Int
  ): F[Unit] = {
    val scheduleEntriesTag = EventTag("com.goodcover.akkart.schedule.ScheduleDueEntries")

    val tagConsumerId = TagConsumer(scheduleEntriesTag, consumerId)

    val updateRepository: F[Unit]                                                                             =
      journal.processNewEvents {
        case EntityEvent(bucketId, _, ScheduleEntryAdded(entryId, _, dueDate, _)) =>
          for {
            _   <- repository.insertScheduleEntry(bucketId, entryId, dueDate)
            now <- clock
            _   <-
              if (dueDate.isEqual(now) || dueDate.isBefore(now)) {
                buckets(bucketId).fireEntry(entryId)
              } else {
                ().pure[F]
              }
          } yield ()
        case EntityEvent(bucketId, _, ScheduleEntryFired(entryId, _, _, _))       =>
          repository.markScheduleEntryAsFired(bucketId, entryId)

        case EntityEvent(bucketId, _, ScheduleEntryRemoved(entryId, _, _))        =>
          // Remove the entry from cassandra.
          repository.deleteScheduleEntry(bucketId, entryId)
      }
    def fireEntries(from: LocalDateTime, to: LocalDateTime): F[Option[ScheduleEntryRepository.ScheduleEntry]] =
      repository.processEntries(from, to, parallelism) { entry =>
        if (entry.fired)
          ().pure[F]
        else
          buckets(entry.bucketId).fireEntry(entry.entryId)
      }

    val loadOffset: F[LocalDateTime] =
      offsetStore
        .getValue(tagConsumerId)
        .map(_.getOrElse(dayZero.atStartOfDay()))

    def saveOffset(value: LocalDateTime): F[Unit] =
      offsetStore.setValue(tagConsumerId, value)

    for {
      _     <- updateRepository
      from  <- loadOffset
      now   <- clock
      entry <- fireEntries(from.minus(eventualConsistencyDelay.toMillis, ChronoUnit.MILLIS), now)
      _     <- entry.map(_.dueDate).traverse(saveOffset)
    } yield ()
  }

}
