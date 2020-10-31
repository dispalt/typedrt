package com.goodcover.akkart.read

import java.time.Duration

import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.query.{EventEnvelope, Sequence}
import akka.stream.SourceShape
import akka.stream.scaladsl.{GraphDSL, Source}
import com.goodcover.akkart.msg.PersistRepr
import com.goodcover.akkart.serialization.PersistCodec
import com.goodcover.akkart.AkkaPersistenceRuntimeActor
import com.goodcover.akkart.read.AkkaPersistenceJournalQuery.AkkaPersistenceJournalSettings
import com.goodcover.encoding.KeyCodec
import com.goodcover.typedrt.JournalAdapter
import com.goodcover.typedrt.data.EventTag

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

class AkkaPersistenceJournalQuery[O, K, E] private (
  entityName: String = "",
  system: ActorSystem,
  adapter: JournalAdapter[O],
  settings: AkkaPersistenceJournalSettings
)(
  implicit serde: PersistCodec[E],
  K: KeyCodec[K])
    extends JournalQueryUnscoped[O, K, E]
    with JournalQueryScoped[O, K, E] {

  private val readJournal = adapter.createReadJournal

  /**
    * We could handle changes here differently, like for instance we could not emit a failed element, or we could log
    * and not emit, or we could do something else.
    *
    * @param inner the input source.
    */
  private def createSource(inner: Source[EventEnvelope, NotUsed]): Source[JournalEntry[O, K, E], NotUsed] =
    inner.mapAsync(1) {
      case EventEnvelope(offset, persistenceId, sequenceNr, event) =>
        offset match {
          case adapter.journalOffset(offsetValue) =>
            val index    =
              persistenceId.indexOf(AkkaPersistenceRuntimeActor.PersistenceIdSeparator)
            val idString = persistenceId.substring(index + 1, persistenceId.length)
            K.decode(idString) match {
              case Some(value) =>
                event match {
                  case repr: PersistRepr =>
                    serde
                      .decodeWrapper(repr)
                      .map { m =>
                        JournalEntry[O, K, E](offsetValue, EntityEvent(value, sequenceNr, m))
                      }
                      .fold(
                        err => Future.failed(new Exception(s"Failed to decode event (${repr.id}): ${err.getMessage}")),
                        Future.successful
                      )
                  case other             =>
                    Future.failed(
                      new IllegalArgumentException(
                        s"Unexpected persistent representation [$other] at sequenceNr = [$sequenceNr], persistenceId = [$persistenceId]"
                      )
                    )
                }

              case None        => Future.failed(new IllegalArgumentException(s"Failed to decode entity id [$idString]"))
            }

          case other                              =>
            Future.failed(
              new IllegalArgumentException(
                s"Unexpected offset of type [${other.getClass}] at sequenceNr = [$sequenceNr], persistenceId = [$persistenceId]"
              )
            )
        }
    }

  private def createLongSource(inner: Source[EventEnvelope, NotUsed]): Source[JournalEntry[Unit, K, E], NotUsed] =
    inner.mapAsync(1) {
      case EventEnvelope(offset, persistenceId, sequenceNr, event) =>
        offset match {
          case Sequence(seq) =>
            val index    = persistenceId.indexOf(AkkaPersistenceRuntimeActor.PersistenceIdSeparator)
            val idString = persistenceId.substring(index + 1, persistenceId.length)

            K.decode(idString) match {
              case Some(value) =>
                event match {
                  case repr: PersistRepr =>
                    serde
                      .decodeWrapper(repr)
                      .map { m =>
                        JournalEntry[Unit, K, E]((), EntityEvent(value, seq, m))
                      }
                      .fold(Future.failed, Future.successful)
                  case other             =>
                    Future.failed(
                      new IllegalArgumentException(
                        s"Unexpected persistent representation [$other] at sequenceNr = [$sequenceNr], persistenceId = [$persistenceId]"
                      )
                    )
                }
              case None        => Future.failed(new IllegalArgumentException(s"Failed to decode entity id [$idString]"))

            }
          case other         =>
            Future.failed(
              new IllegalArgumentException(
                s"Unexpected offset of type [${other.getClass}] at sequenceNr = [$sequenceNr], persistenceId = [$persistenceId]"
              )
            )
        }
    }

  def events(tags: Seq[EventTag], offset: Option[O]): Source[JournalEntryOrHeartBeat[O, K, E], NotUsed] = {
    merge(tags.map(tag => eventsByTag(tag, offset)), live = true)
  }

  def currentEvents(tags: Seq[EventTag], offset: Option[O]): Source[JournalEntry[O, K, E], NotUsed] = {
    merge(tags.map(tag => currentEventsByTag(tag, offset)), live = false).map(_.asInstanceOf[JournalEntry[O, K, E]])
  }

  def eventsByTag(tag: EventTag, offset: Option[O]): Source[JournalEntry[O, K, E], NotUsed] =
    createSource(
      readJournal
        .eventsByTag(tag.value, adapter.journalOffset(offset))
    )

  def currentEventsByTag(tag: EventTag, offset: Option[O]): Source[JournalEntry[O, K, E], NotUsed] =
    createSource(readJournal.currentEventsByTag(tag.value, adapter.journalOffset(offset)))

  def eventsByPersistenceId(
    id: K,
    offset: Option[Long],
    to: Option[Long]
  ): Source[JournalEntry[Unit, K, E], NotUsed] = {

    createLongSource(
      readJournal.eventsByPersistenceId(
        AkkaPersistenceRuntimeActor.combineId(entityName, K.apply(id)),
        offset.getOrElse(0L),
        to.getOrElse(Long.MaxValue)
      )
    )
  }

  def currentEventsByPersistenceId(
    id: K,
    offset: Option[Long],
    to: Option[Long]
  ): Source[JournalEntry[Unit, K, E], NotUsed] = {
    createLongSource(
      readJournal.currentEventsByPersistenceId(
        AkkaPersistenceRuntimeActor.combineId(entityName, K.apply(id)),
        offset.getOrElse(0L),
        to.getOrElse(Long.MaxValue)
      )
    )
  }

  def eventsByPersistenceId(
    entityName: String,
    id: K,
    offset: Option[Long],
    to: Option[Long]
  ): Source[JournalEntry[Unit, K, E], NotUsed] = {

    createLongSource(
      readJournal.eventsByPersistenceId(
        AkkaPersistenceRuntimeActor.combineId(entityName, K.apply(id)),
        offset.getOrElse(0L),
        to.getOrElse(Long.MaxValue)
      )
    )
  }

  def currentEventsByPersistenceId(
    entityName: String,
    id: K,
    offset: Option[Long],
    to: Option[Long]
  ): Source[JournalEntry[Unit, K, E], NotUsed] =
    createLongSource(
      readJournal.currentEventsByPersistenceId(
        AkkaPersistenceRuntimeActor.combineId(entityName, K.apply(id)),
        offset.getOrElse(0L),
        to.getOrElse(Long.MaxValue)
      )
    )

  private[this] def merge(
    inputs: Seq[Source[JournalEntry[O, K, E], NotUsed]],
    live: Boolean,
  ): Source[JournalEntryOrHeartBeat[O, K, E], NotUsed] = {
    val graph = GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val mergeStage =
        builder.add(new MergeJournalsStage[O, K, E](inputs.size, live, settings.eventualConsistencyDuration))
      for (input <- inputs) {
        input ~> mergeStage
      }
      SourceShape(mergeStage.out)
    }
    Source.fromGraph(graph)
  }
}

object AkkaPersistenceJournalQuery {

  def apply[O, K: KeyCodec, E: PersistCodec](
    typeName: String,
    system: ActorSystem,
    journalAdapter: JournalAdapter[O],
    settings: AkkaPersistenceJournalSettings,
  ): JournalQueryScoped[O, K, E] =
    new AkkaPersistenceJournalQuery(typeName, system, journalAdapter, settings)

  def apply[O, K: KeyCodec, E: PersistCodec](
    system: ActorSystem,
    journalAdapter: JournalAdapter[O],
    settings: AkkaPersistenceJournalSettings,
  ): JournalQueryUnscoped[O, K, E] =
    new AkkaPersistenceJournalQuery(system = system, adapter = journalAdapter, settings = settings)

  case class AkkaPersistenceJournalSettings(eventualConsistencyDuration: Option[Duration])

  object AkkaPersistenceJournalSettings {
    def apply(): AkkaPersistenceJournalSettings = AkkaPersistenceJournalSettings(None)

    /**
      * Reads config from `goodcover.akka-runtime`, see reference.conf for details
      * @param system Actor system to get config from
      * @return default settings
      */
    def apply(system: ActorSystem): AkkaPersistenceJournalSettings = {
      val config = system.settings.config.getConfig("goodcover.akka-runtime")

      AkkaPersistenceJournalSettings(Try(config.getDuration("eventual-consistency-duration")).toOption)
    }
  }

}
