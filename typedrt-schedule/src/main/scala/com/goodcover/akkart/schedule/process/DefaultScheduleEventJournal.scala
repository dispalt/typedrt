package com.goodcover.akkart.schedule.process

import java.util.UUID

import cats.syntax.functor._
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink}
import cats.effect.Effect
import com.goodcover.fxn._
import com.goodcover.akkart.schedule.{ScheduleBucketId, ScheduleEvent}
import com.goodcover.akkart.read.{CommittableEventJournalQuery, EntityEvent, JournalEntry}
import com.goodcover.typedrt.data.{Committable, ConsumerId, EventTag}

object DefaultScheduleEventJournal {

  def apply[F[_]: Effect](
    consumerId: ConsumerId,
    parallelism: Int,
    aggregateJournal: CommittableEventJournalQuery[F, UUID, ScheduleBucketId, ScheduleEvent],
    eventTag: EventTag
  )(
    implicit materializer: Materializer
  ): DefaultScheduleEventJournal[F] =
    new DefaultScheduleEventJournal(consumerId, parallelism, aggregateJournal, eventTag)
}

class DefaultScheduleEventJournal[F[_]: Effect](
  consumerId: ConsumerId,
  parallelism: Int,
  aggregateJournal: CommittableEventJournalQuery[F, UUID, ScheduleBucketId, ScheduleEvent],
  eventTag: EventTag
)(
  implicit materializer: Materializer)
    extends ScheduleEventJournal[F] {

  override def processNewEvents(f: EntityEvent[ScheduleBucketId, ScheduleEvent] => F[Unit]): F[Unit] =
    Effect[F].fromFuture {
      aggregateJournal
        .currentEventsByTag(eventTag, consumerId)
        .mapAsync(parallelism)(_.map(_.event).traverse(f).unsafeToFuture())
        .fold(Committable.unit[F])(Keep.right)
        .mapAsync(1)(_.commit.unsafeToFuture())
        .runWith(Sink.ignore)
    }.void
}
