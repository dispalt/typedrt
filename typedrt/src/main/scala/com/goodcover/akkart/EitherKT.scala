package com.goodcover.akkart

import cats.data.EitherT
import cats.~>
import cats.implicits._
import cats.tagless.FunctorK
import cats.tagless.syntax.functorK._
import com.dispalt.tagless.util.{PairE, WireProtocol}
import com.dispalt.tagless.util.WireProtocol.{Decoder, Encoded, Encoder, Invocation}

import scala.util.Try

/**
  * Higher-kinded transformer for EitherT
  */
final case class EitherKT[M[_[_]], A, F[_]](value: M[EitherT[F, A, *]]) extends AnyVal {

  def unwrap(implicit M: FunctorK[M]): M[λ[α => F[Either[A, α]]]] =
    value.mapK(new (EitherT[F, A, *] ~> λ[α => F[Either[A, α]]]) {
      override def apply[X](fa: EitherT[F, A, X]): F[Either[A, X]] = fa.value
    })

  def mapK[G[_]](fg: F ~> G)(implicit M: FunctorK[M]): EitherKT[M, A, G] =
    EitherKT(M.mapK(value)(new (EitherT[F, A, *] ~> EitherT[G, A, *]) {

      override def apply[X](fa: EitherT[F, A, X]): EitherT[G, A, X] =
        fa.mapK(fg)
    }))
}

object EitherKT {

  implicit def wireProtocol[M[_[_]]: FunctorK, A](
    implicit M: WireProtocol[M],
    D: Decoder[A],
    E: Encoder[A],
  ): WireProtocol[EitherKT[M, A, *[_]]] =
    new WireProtocol[EitherKT[M, A, *[_]]] {

      override final val encoder: EitherKT[M, A, Encoded] =
        EitherKT[M, A, Encoded] {
          M.encoder.mapK(new (Encoded ~> EitherT[Encoded, A, *]) {
            override def apply[B](ma: Encoded[B]): EitherT[Encoded, A, B] =
              EitherT[Encoded, A, B] {
                val (bytes, resultDecoder) = ma
                (
                  bytes,
                  new Decoder[Either[A, B]] {
                    override def apply(ab: Array[Byte]): Try[Either[A, B]] = ab(0) match {
                      case 1 => resultDecoder.apply(ab.drop(1)).map(_.asRight[A])
                      case 0 => D.apply(ab.drop(1)).map(_.asLeft[B])
                    }
                  }
                )

              }
          })
        }

      override final val decoder: Decoder[PairE[Invocation[EitherKT[M, A, *[_]], *], Encoder]] =
        (ab: Array[Byte]) => {
          M.decoder.apply(ab).map { p =>
            val (invocation, encoder) = (p.first, p.second)

            val eitherKInvocation =
              new Invocation[EitherKT[M, A, *[_]], Either[A, p.A]] {
                override def run[G[_]](target: EitherKT[M, A, G]): G[Either[A, p.A]] =
                  invocation.run(target.value).value

                override def toString: String = invocation.toString
              }

            val eitherEncoder = new Encoder[Either[A, p.A]] {

              override def apply(enc: Either[A, p.A]): Array[Byte] = enc match {
                case Right(a) => (1: Byte) +: encoder.apply(a)
                case Left(r)  => (0: Byte) +: E.apply(r)
              }

            }

            PairE(eitherKInvocation, eitherEncoder)
          }
        }
    }

  implicit def functorK[M[_[_]]: FunctorK, A]: FunctorK[EitherKT[M, A, *[_]]] =
    new FunctorK[EitherKT[M, A, *[_]]] {

      override def mapK[F[_], G[_]](af: EitherKT[M, A, F])(fk: ~>[F, G]): EitherKT[M, A, G] =
        af.mapK(fk)
    }

  object syntax {

    final implicit class EitherKSyntaxImpl[M[_[_]], F[_], A](val self: M[EitherT[F, A, *]]) extends AnyVal {
      def toEitherK: EitherKT[M, A, F] = EitherKT(self)
    }
  }
}
