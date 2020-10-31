package com.goodcover.akkart.schedule

import java.time.LocalDateTime

import com.goodcover.akkart.schedule.ScheduleEntryRepository.ScheduleEntry

trait ScheduleEntryRepository[F[_]] {
  def insertScheduleEntry(scheduleBucketId: ScheduleBucketId, entryId: String, dueDate: LocalDateTime): F[Unit]
  def markScheduleEntryAsFired(scheduleBucketId: ScheduleBucketId, entryId: String): F[Unit]
  def deleteScheduleEntry(scheduleBucketId: ScheduleBucketId, entryId: String): F[Unit]

  def processEntries(
    from: LocalDateTime,
    to: LocalDateTime,
    parallelism: Int
  )(
    f: ScheduleEntry => F[Unit]
  ): F[Option[ScheduleEntry]]
}

object ScheduleEntryRepository {

  final case class ScheduleEntry(
    bucketId: ScheduleBucketId,
    entryId: String,
    dueDate: LocalDateTime,
    timeBucket: String,
    fired: Boolean)
}
