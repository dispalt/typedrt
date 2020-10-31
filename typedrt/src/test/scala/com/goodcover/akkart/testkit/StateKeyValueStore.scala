package com.goodcover.akkart.testkit

import cats.mtl.Stateful
import com.goodcover.typedrt.data.KeyValueStore
import monocle.Lens

object StateKeyValueStore {

  final class Builder[F[_]] {

    def apply[S: Stateful[F, ?], K, A](lens: Lens[S, Map[K, A]]): KeyValueStore[F, K, A] =
      new StateKeyValueStore(lens)
  }
  def apply[F[_]]: Builder[F] = new Builder[F]
}

class StateKeyValueStore[F[_]: Stateful[?[_], S], S, K, A](lens: Lens[S, Map[K, A]]) extends KeyValueStore[F, K, A] {
  private val F = lens.transformMonadState(Stateful[F, S])

  override def setValue(key: K, value: A): F[Unit] =
    F.modify(_.updated(key, value))

  override def getValue(key: K): F[Option[A]] =
    F.inspect(_.get(key))

  override def deleteValue(key: K): F[Unit] =
    F.modify(_ - key)
}
