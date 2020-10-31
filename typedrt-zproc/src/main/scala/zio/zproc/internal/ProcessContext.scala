package zio.zproc.internal

import zio.{Chunk, Promise, UIO}

case class ProcessContext(
  events: Chunk[JournalEvent],
  cursor: Long,
  persistedSeqNr: Long,
  maybePromise: Option[(JournalEvent, Promise[Nothing, Unit])]) {

  private def pushPromise(ev: JournalEvent): UIO[ProcessContext] = {
    Promise.make[Nothing, Unit].map { promise =>
      copy(maybePromise = Some((ev, promise)))
    }
  }

  private def completePromise: UIO[ProcessContext] = {
    maybePromise.get._2.succeed(()) *> UIO(copy(maybePromise = None))
  }

  def appendEvent(ev: JournalEvent, replay: Boolean): UIO[ProcessContext] = {
    val newSt = ev match {
      case sp: JournalEvent.StartProcess                    => UIO(this)
      case JournalEvent.AwaitConditionSync(id)              =>
        pushPromise(ev)

      case JournalEvent.AwaitConditionSucceeded(_, awaitId) =>
        completePromise

      case JournalEvent.DelayConditionSync(id, _, _)        =>
        pushPromise(ev)

      case JournalEvent.DelayConditionSucceeded(_, awaitId) =>
        completePromise

    }

    newSt.map { newState =>
      // This is the cursor for the program not the event
      val newCursor = cursor + (if (replay) 0 else 1)
      newState.copy(events = events :+ ev, persistedSeqNr = persistedSeqNr + 1, cursor = newCursor)
    }
  }

  def incAndEventId(num: Int): ProcessContext = copy(cursor = cursor + num)

}
