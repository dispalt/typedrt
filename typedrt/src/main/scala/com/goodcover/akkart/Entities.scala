package com.goodcover.akkart

import cats.tagless.FunctorK

sealed abstract class Entities[K, M[_[_]], F[_]] {
  def apply(k: K): M[F]
}

object Entities {
  type Rejectable[K, M[_[_]], F[_], R] = Entities[K, M, λ[α => F[Either[R, α]]]]

  def apply[K, M[_[_]], F[_]](kmf: K => M[F]): Entities[K, M, F] = new Entities[K, M, F] {
    override def apply(k: K): M[F] = kmf(k)
  }

  def fromEitherKT[K, M[_[_]]: FunctorK, F[_], R](mfr: K => EitherKT[M, R, F]): Rejectable[K, M, F, R] =
    new Rejectable[K, M, F, R] {
      override def apply(k: K): M[λ[α => F[Either[R, α]]]] = mfr(k).unwrap
    }
}
