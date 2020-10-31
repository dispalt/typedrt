package com.goodcover.akkart

import cats.Monad
import cats.arrow.FunctionK
import cats.mtl.{Stateful}
import monocle.Lens

package object testkit {

  final implicit class AecorMonocleLensOps[S, A](lens: Lens[S, A]) {

    def transformMonadState[F[_]](f: Stateful[F, S]): Stateful[F, A] = new Stateful[F, A] {
      override val monad: Monad[F]              = f.monad
      override def get: F[A]                    = f.inspect(lens.get)
      override def set(s: A): F[Unit]           = f.modify(lens.set(s))
      override def inspect[C](bc: A => C): F[C] = f.inspect(s => bc(lens.get(s)))
      override def modify(bb: A => A): F[Unit]  = f.modify(lens.modify(bb))
    }
  }

  type ActionRunner[F[_], K, S, E] = FunctionK[ActionT[F, K, S, E, ?], F]
}
