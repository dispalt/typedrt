package com.goodcover.akkart.testkit

import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.{Monad, Order}
import cats.data.{Chain, NonEmptyChain}
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.mtl.Stateful
import com.goodcover.akkart.read.EntityEvent
import com.goodcover.akkart.testkit.StateEventJournal.State
import com.goodcover.typedrt.data.{Committable, ConsumerId, EventTag, Tagging}
import monocle.Lens

object StateEventJournal {

  implicit val orderInstance: Order[EventTag] = Order.fromOrdering(Ordering[String].on(_.value))

  final case class State[K, E](
    eventsByKey: Map[K, Chain[E]],
    eventsByTag: Map[EventTag, Chain[EntityEvent[K, E]]],
    consumerOffsets: Map[(EventTag, ConsumerId), Int]) {

    def getConsumerOffset(tag: EventTag, consumerId: ConsumerId): Int                      =
      consumerOffsets.getOrElse(tag -> consumerId, 0)

    def setConsumerOffset(tag: EventTag, consumerId: ConsumerId, offset: Int): State[K, E] =
      copy(consumerOffsets = consumerOffsets.updated(tag -> consumerId, offset))

    def getEventsByTag(tag: EventTag, offset: Int): Chain[(Int, EntityEvent[K, E])] = {
      val stream = LazyList
        .from(1)
        .zip(
          eventsByTag
            .getOrElse(tag, Chain.empty)
            .toList
        )
        .drop(offset - 1)
      Chain.fromSeq(stream)
    }

    def appendEvents(key: K, offset: Long, events: NonEmptyChain[TaggedEvent[E]]): State[K, E] = {
      val updatedEventsById = eventsByKey
        .updated(key, eventsByKey.getOrElse(key, Chain.empty) ++ events.map(_.event).toChain)

      val newEventsByTag: Map[EventTag, Chain[EntityEvent[K, E]]] = events.toChain.zipWithIndex
        .flatMap {
          case (e, idx) =>
            Chain.fromSeq(e.tags.toSeq).map(t => t -> EntityEvent(key, idx + offset, e.event))
        }
        .groupBy(_._1)
        .view
        .mapValues(_.map(_._2).toChain)
        .toMap
      copy(
        eventsByKey = updatedEventsById,
        eventsByTag =
          eventsByTag |+| newEventsByTag
      )
    }
  }

  object State {
    def init[I, E]: State[I, E] = State(Map.empty, Map.empty, Map.empty)
  }

  def apply[F[_]: Stateful[?[_], A], K, A, E](
    lens: Lens[A, State[K, E]],
    tagging: Tagging[K]
  ): StateEventJournal[F, K, A, E] =
    new StateEventJournal(lens, tagging)

}

final class StateEventJournal[F[_], K, S, E](
  lens: Lens[S, State[K, E]],
  tagging: Tagging[K],
)(
  implicit MS: Stateful[F, S])
    extends EventJournal[F, K, E] {
  final private implicit val monad = MS.monad
  final private val F              = lens.transformMonadState(MS)

  override def append(key: K, sequenceNr: Long, events: NonEmptyChain[E]): F[Unit] =
    F.modify(_.appendEvents(key, sequenceNr, events.map(e => TaggedEvent(e, tagging.apply(key)))))

  override def foldById[A](id: K, sequenceNr: Long, zero: A)(f: (A, E) => A): F[A] =
    F.inspect(
      _.eventsByKey
        .get(id)
        .map(_.toVector.drop(sequenceNr.toInt - 1))
        .getOrElse(Vector.empty)
    ).map(_.foldLeft(zero)(f))

  def currentEventsByTag(tag: EventTag, consumerId: ConsumerId): Processable[F, EntityEvent[K, E]] =
    new Processable[F, EntityEvent[K, E]] {

      override def process(f: EntityEvent[K, E] => F[Unit]): F[Unit] =
        for {
          state <- F.get
          committedOffset = state.getConsumerOffset(tag, consumerId)
          result          = state.getEventsByTag(tag, committedOffset + 1)
          _ <- result.traverse {
            case (offset, e) =>
              for {
                _ <- f(e)
                _ <- F.modify(_.setConsumerOffset(tag, consumerId, offset))
              } yield ()
          }
        } yield ()
    }

  def currentEventsByTagSource(
    tag: EventTag,
    consumerId: ConsumerId
  ): F[Source[Committable[F, EntityEvent[K, E]], NotUsed]] = {
    F.get.flatMap { state =>
      val committedOffset = state.getConsumerOffset(tag, consumerId)
      val result          = state.getEventsByTag(tag, committedOffset + 1)

      result.lastOption match {
        case Some((offset, _)) =>
          F.monad.unit.map(
            _ =>
              Source(result.map {
                case (offset, ev) =>
                  Committable(F.modify(_.setConsumerOffset(tag, consumerId, offset)), ev)
              }.toVector)
          )
        case None              =>
          F.monad.unit.map(_ => Source.empty)
      }
    }
  }
}

final case class TaggedEvent[E](event: E, tags: Set[EventTag])
