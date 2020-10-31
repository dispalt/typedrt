package zio.zproc.internal

import java.time.Instant
import java.util.UUID

import zio.UIO
import zio.duration.Duration

sealed abstract class JournalEvent {
  def id: Long
}

object JournalEvent {
  case class StartProcess(runId: UUID, id: Long, queue: String) extends JournalEvent

  case class AwaitConditionSync(id: Long)                     extends JournalEvent
  case class AwaitConditionSucceeded(id: Long, awaitId: Long) extends JournalEvent {}

  case class DelayConditionSync(id: Long, duration: Duration, startTime: Instant) extends JournalEvent
  case class DelayConditionSucceeded(id: Long, awaitId: Long)                     extends JournalEvent
}
