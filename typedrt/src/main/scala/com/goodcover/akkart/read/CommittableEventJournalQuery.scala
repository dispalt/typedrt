package com.goodcover.akkart.read

import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.effect.Effect
import cats.effect.syntax._
import com.goodcover.fxn._
import com.goodcover.typedrt.data._

final class CommittableEventJournalQuery[F[_]: Effect, O, K, E](
  underlying: JournalQuery[O, K, E],
  offsetStore: KeyValueStore[F, TagConsumer, O]) {

  def eventsByTag(tag: EventTag, consumerId: ConsumerId): Source[Committable[F, JournalEntry[O, K, E]], NotUsed] = {
    val tagConsumerId = TagConsumer(tag, consumerId)
    Source
      .single(NotUsed)
      .mapAsync(1) { _ =>
        offsetStore.getValue(tagConsumerId).unsafeToFuture()
      }
      .flatMapConcat { storedOffset =>
        underlying.eventsByTag(tag, storedOffset)
      }
      .map(x => Committable(offsetStore.setValue(tagConsumerId, x.offset), x))
  }

  def currentEventsByTag(
    tag: EventTag,
    consumerId: ConsumerId
  ): Source[Committable[F, JournalEntry[O, K, E]], NotUsed] = {
    val tagConsumerId = TagConsumer(tag, consumerId)
    Source
      .single(NotUsed)
      .mapAsync(1) { _ =>
        offsetStore.getValue(tagConsumerId).unsafeToFuture()
      }
      .flatMapConcat { storedOffset =>
        underlying.currentEventsByTag(tag, storedOffset)
      }
      .map(x => Committable(offsetStore.setValue(tagConsumerId, x.offset), x))
  }
}

object CommittableEventJournalQuery {

  def apply[F[_]: Effect, Offset, K, E](
    underlying: JournalQuery[Offset, K, E],
    offsetStore: KeyValueStore[F, TagConsumer, Offset]
  ): CommittableEventJournalQuery[F, Offset, K, E] =
    new CommittableEventJournalQuery(underlying, offsetStore)
}
