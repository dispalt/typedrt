package zio.zproc

import zio.zproc.internal.{JournalEvent, ProcessContext}
import zio.clock.Clock
import zio.{Promise, Queue, Ref, UIO, ZIO}

trait Ctx[St] {
  def state: Ref[St]

  def workflowContext: Ref[ProcessContext]

  def modify(fn: St => St): UIO[Ctx[St]] = state.update(fn).as(this)

  def clock: Clock.Service

  def instant = clock.instant

  def inboundQueue: Queue[St]

  def logEvent(ev: JournalEvent): UIO[Unit] = {
    workflowContext.get.flatMap { pc =>
      pc.appendEvent(ev, replay = false).flatMap { newPc =>
        workflowContext.set(newPc)
      }
    }
  }

  def processCursor: UIO[Long] = workflowContext.get.map(_.cursor)

  def incAndEventId(num: Int): UIO[Long] =
    workflowContext.modify(s => (s.incAndEventId(num).cursor, s.incAndEventId(num)))

  def persistedSeqNr: UIO[Long] = workflowContext.get.map(_.persistedSeqNr)

  def waitCondition: UIO[Unit] = workflowContext.get.flatMap(_.maybePromise.get._2.await).unit

  def incCursor(inc: Long): UIO[Unit] = workflowContext.get.map(wc => wc.copy(cursor = wc.cursor + inc))

  def getOpenState: UIO[Option[JournalEvent]] = workflowContext.get.map(_.maybePromise.map(_._1))

}
