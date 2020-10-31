package com.goodcover.akkart.read

import cats.Monad
import cats.implicits._
import com.goodcover.typedrt.data.KeyValueStore

object Projection {
  final case class Versioned[A](version: Long, a: A)

  trait Failure[F[_], K, E, S] {
    def missingEvent(key: K, seqNr: Long): F[Unit]
  }

  def apply[F[_]: Monad, Key, Event, State](
    store: KeyValueStore[F, Key, Versioned[State]],
    zero: Event => State,
    update: (Event, State) => State,
    failure: Failure[F, Key, Event, State]
  ): EntityEvent[Key, Event] => F[Unit] = {
    case input @ EntityEvent(key, seqNr, event) =>
      for {
        state <- store.getValue(key)
        currentVersion = state.fold(0L)(_.version)
        _ <-
          if (seqNr <= currentVersion) {
            ().pure[F]
          } else if (seqNr == currentVersion + 1) {
            val result = state
              .map(_.a)
              .fold(zero(event))(update(event, _))
            store.setValue(key, Versioned(currentVersion + 1, result))
          } else {
            failure.missingEvent(key, currentVersion + 1)
          }
      } yield ()
  }
}
