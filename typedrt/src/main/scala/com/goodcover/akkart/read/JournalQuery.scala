package com.goodcover.akkart.read

import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.effect.Effect
import com.goodcover.typedrt.data.{EventTag, KeyValueStore, TagConsumer}

sealed abstract class JournalEntryOrHeartBeat[O, K, E] {
  def offset: O
}

final case class JournalEntry[O, K, A](offset: O, event: EntityEvent[K, A]) extends JournalEntryOrHeartBeat[O, K, A] {
  def map[B](func: A => B): JournalEntry[O, K, B] = copy(event = event.map(func))
}

final case class HeartBeat[O, K, E](offset: O) extends JournalEntryOrHeartBeat[O, K, E]

case class EntityEvent[K, E](key: K, seqNr: Long, ev: E) {

  def map[B](func: E => B): EntityEvent[K, B] = {
    copy(ev = func(ev))
  }
}

trait JournalQuery[Offset, K, E] {

  def events(tags: Seq[EventTag], offset: Option[Offset]): Source[JournalEntryOrHeartBeat[Offset, K, E], NotUsed]

  def currentEvents(tags: Seq[EventTag], offset: Option[Offset]): Source[JournalEntry[Offset, K, E], NotUsed]

  def eventsByTag(tag: EventTag, offset: Option[Offset]): Source[JournalEntry[Offset, K, E], NotUsed]

  def currentEventsByTag(tag: EventTag, offset: Option[Offset]): Source[JournalEntry[Offset, K, E], NotUsed]

  def committable[F[_]: Effect](
    offsetStore: KeyValueStore[F, TagConsumer, Offset]
  ): CommittableEventJournalQuery[F, Offset, K, E] =
    new CommittableEventJournalQuery(this, offsetStore)
}

trait JournalQueryUnscoped[Offset, K, E] extends JournalQuery[Offset, K, E] {

  def eventsByPersistenceId(
    entityName: String,
    id: K,
    offset: Option[Long],
    to: Option[Long]
  ): Source[JournalEntry[Unit, K, E], NotUsed]

  def currentEventsByPersistenceId(
    entityName: String,
    id: K,
    offset: Option[Long],
    to: Option[Long]
  ): Source[JournalEntry[Unit, K, E], NotUsed]

}

trait JournalQueryScoped[Offset, K, E] extends JournalQuery[Offset, K, E] {

  def eventsByPersistenceId(id: K, offset: Option[Long], to: Option[Long]): Source[JournalEntry[Unit, K, E], NotUsed]

  def currentEventsByPersistenceId(
    id: K,
    offset: Option[Long],
    to: Option[Long]
  ): Source[JournalEntry[Unit, K, E], NotUsed]
}
