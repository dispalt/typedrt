package com.goodcover.akkart.schedule.process

import java.util.UUID

import com.goodcover.akkart.schedule.{ScheduleBucketId, ScheduleEvent}
import com.goodcover.akkart.read.{EntityEvent, JournalEntry}

trait ScheduleEventJournal[F[_]] {
  def processNewEvents(f: EntityEvent[ScheduleBucketId, ScheduleEvent] => F[Unit]): F[Unit]
}
