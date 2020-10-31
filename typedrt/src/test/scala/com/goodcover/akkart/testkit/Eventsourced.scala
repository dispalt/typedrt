package com.goodcover.akkart.testkit

import cats.effect.Sync
import cats.implicits._
import cats.tagless.FunctorK
import cats.tagless.syntax.functorK._
import com.goodcover.akkart.{BehaviorM, EitherKT}
import com.goodcover.typedrt.data.KeyValueStore

object Eventsourced {

  def apply[M[_[_]]: FunctorK, F[_]: Sync, S, E, K](
    entityBehavior: BehaviorM[M, F, K, S, E],
    journal: EventJournal[F, K, E],
    snapshotting: Option[Snapshotting[F, K, S]] = Option.empty
  ): K => F[M[F]] = { key =>
    for {
      actionRunner <- DefaultActionRunner.create[F, K, S, E](
        key,
        entityBehavior.create(key),
        entityBehavior.update,
        entityBehavior.recoveryComplete,
        journal,
        snapshotting
      )
    } yield entityBehavior.actions.mapK(actionRunner)
  }

  sealed abstract class Entities[K, M[_[_]], F[_]] {
    def apply(k: K): M[F]
  }

  object Entities {
    type Rejectable[K, M[_[_]], F[_], R] = Entities[K, M, λ[α => F[Either[R, α]]]]

    def apply[K, M[_[_]], F[_]](kmf: K => M[F]): Entities[K, M, F] = new Entities[K, M, F] {
      override def apply(k: K): M[F] = kmf(k)
    }

    def fromEitherK[K, M[_[_]]: FunctorK, F[_], R](mfr: K => EitherKT[M, R, F]): Rejectable[K, M, F, R] =
      new Rejectable[K, M, F, R] {
        override def apply(k: K): M[λ[α => F[Either[R, α]]]] = mfr(k).unwrap
      }
  }

  final case class Snapshotting[F[_], K, S](snapshotEach: Long, store: KeyValueStore[F, K, InternalState[S]])
  type EntityKey = String

  final case class InternalState[S](entityState: S, version: Long) {
    def withEntityState(s: S): InternalState[S] = copy(entityState = s)
  }

}
