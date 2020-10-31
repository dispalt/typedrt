package com.goodcover.akkart

import cats.{Applicative, Monad, MonadError}
import cats.data.{EitherT, NonEmptyList}
import cats.effect.Sync
import com.goodcover.encoding.KeyCodec

trait MonadAction[F[_], C, S, E] extends Monad[F] {
  def ctx: F[KeyedCtx[C]]
  def key: F[C]                            = map(ctx)(_.key)
  def read: F[S]
  def append(es: E, other: E*): F[Unit]
  def append(es: NonEmptyList[E]): F[Unit] = append(es.head, es.tail: _*)
  def reset: F[Unit]
  def set(s: S): F[Unit]
  def modify(fun: S => S): F[Unit]
}

trait MonadActionReject[F[_], C, S, E, R] extends MonadAction[F, C, S, E] {
  def reject[A](r: R): F[A]
}

object MonadActionReject {

  implicit def eitherTMonadActionRejectInstance[I[_]: Applicative, C, S, E, R](
    implicit F: MonadAction[I, C, S, E],
    eitherTMonad: Monad[EitherT[I, R, ?]]
  ): MonadActionReject[EitherT[I, R, ?], C, S, E, R] =
    new MonadActionReject[EitherT[I, R, ?], C, S, E, R] {

      override def modify(fun: S => S): EitherT[I, R, Unit] = EitherT.right(F.modify(fun))
      override def set(s: S): EitherT[I, R, Unit]           = EitherT.right(F.set(s))
      override def ctx: EitherT[I, R, KeyedCtx[C]]          = EitherT.right(F.ctx)
      override def reject[A](r: R): EitherT[I, R, A]        = EitherT.leftT(r)
      override def read: EitherT[I, R, S]                   = EitherT.right(F.read)

      override def append(es: E, other: E*): EitherT[I, R, Unit]                =
        EitherT.right(F.append(es, other: _*))
      override def reset: EitherT[I, R, Unit]                                   = EitherT.right(F.reset)
      override def pure[A](x: A): EitherT[I, R, A]                              = EitherT.pure[I, R](x)
      override def map[A, B](fa: EitherT[I, R, A])(f: A => B): EitherT[I, R, B] = fa.map(f)

      override def flatMap[A, B](fa: EitherT[I, R, A])(f: A => EitherT[I, R, B]): EitherT[I, R, B] =
        fa.flatMap(f)

      override def tailRecM[A, B](a: A)(f: A => EitherT[I, R, Either[A, B]]): EitherT[I, R, B] =
        eitherTMonad.tailRecM(a)(f)
    }
}

trait MonadActionLift[I[_], F[_], C, S, E] extends MonadAction[I, C, S, E] {
  def liftF[A](fa: F[A]): I[A]

  /** Added because of the commonality with F being an Effect[F] */
  def raiseException[A](a: Throwable)(implicit F: MonadError[F, Throwable]): I[A] = liftF(F.raiseError[A](a))

  def delay[A](a: => A)(implicit F: Sync[F]): I[A] = liftF(F.delay(a))

  def rejectableLiftInstance[R](
    implicit eitherTMonad: Monad[EitherT[I, R, ?]]
  ): MonadActionLiftReject[EitherT[I, R, ?], F, C, S, E, R] = {
    import MonadActionLiftReject._
    eitherTMonadActionLiftRejectInstance[I, F, C, S, E, R](this, eitherTMonad)
  }
}

trait MonadActionLiftReject[I[_], F[_], C, S, E, R]
    extends MonadActionLift[I, F, C, S, E]
    with MonadActionReject[I, C, S, E, R]

object MonadActionLiftReject {

  implicit def eitherTMonadActionLiftRejectInstance[I[_], F[_], C, S, E, R](
    implicit I: MonadActionLift[I, F, C, S, E],
    eitherTMonad: Monad[EitherT[I, R, ?]]
  ): MonadActionLiftReject[EitherT[I, R, ?], F, C, S, E, R] =
    new MonadActionLiftReject[EitherT[I, R, ?], F, C, S, E, R] {

      override def modify(fun: S => S): EitherT[I, R, Unit] = EitherT.right(I.modify(fun))

      override def set(s: S): EitherT[I, R, Unit]       = EitherT.right(I.set(s))
      override def ctx: EitherT[I, R, KeyedCtx[C]]      = EitherT.right(I.ctx)
      override def reject[A](r: R): EitherT[I, R, A]    = EitherT.leftT(r)
      override def liftF[A](fa: F[A]): EitherT[I, R, A] = EitherT.right(I.liftF(fa))
      override def read: EitherT[I, R, S]               = EitherT.right(I.read)

      override def append(es: E, other: E*): EitherT[I, R, Unit]                =
        EitherT.right(I.append(es, other: _*))
      override def reset: EitherT[I, R, Unit]                                   = EitherT.right(I.reset)
      override def pure[A](x: A): EitherT[I, R, A]                              = EitherT.pure[I, R](x)
      override def map[A, B](fa: EitherT[I, R, A])(f: A => B): EitherT[I, R, B] = fa.map(f)

      override def flatMap[A, B](fa: EitherT[I, R, A])(f: A => EitherT[I, R, B]): EitherT[I, R, B] =
        fa.flatMap(f)

      override def tailRecM[A, B](a: A)(f: A => EitherT[I, R, Either[A, B]]): EitherT[I, R, B] =
        eitherTMonad.tailRecM(a)(f)
    }
}
