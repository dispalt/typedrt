package com.goodcover.akkart

import cats.{~>, Applicative, Functor, Monad, MonadError}
import cats.data.{Chain, NonEmptyChain}
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.traverse._
import cats.syntax.applicative._
import cats.syntax.either._
import cats.instances.either._
import cats.tagless.FunctorK

final class ActionT[F[_], C, S, E, A] private (
  val unsafeRun: (KeyedCtx[C], S, (S, E) => S, Chain[E]) => F[(Chain[E], S, A)])
    extends AnyVal {

  def run(ctx: KeyedCtx[C], current: S, update: (S, E) => S): F[(Chain[E], S, A)] =
    unsafeRun(ctx, current, update, Chain.empty)

  def map[B](f: A => B)(implicit F: Functor[F]): ActionT[F, C, S, E, B] = ActionT { (c, s, u, es) =>
    unsafeRun(c, s, u, es).map(x => (x._1, x._2, f(x._3)))
  }

  def flatMap[B](f: A => ActionT[F, C, S, E, B])(implicit F: Monad[F]): ActionT[F, C, S, E, B] =
    ActionT { (c0, s0, u, es0) =>
      unsafeRun(c0, s0, u, es0)
        .flatMap {
          case (es1, s, a) =>
            f(a).unsafeRun(c0, s, u, es1)
        }
    }

  def sample[Env, E2](
    getEnv: F[Env]
  )(
    update: (Env, E) => E2
  )(
    extract: E2 => E
  )(
    implicit F: Monad[F]
  ): ActionT[F, C, S, E2, A] =
    ActionT.liftF[F, C, S, E2, Env](getEnv).flatMap { env =>
      xmapEvents(update(env, _), extract)
    }

  def xmapEvents[E2](to: E => E2, from: E2 => E)(implicit F: Functor[F]): ActionT[F, C, S, E2, A] =
    ActionT { (c, s, u2, e2s) =>
      val u1 = (sx: S, e1: E) => u2(sx, to(e1))
      unsafeRun(c, s, u1, e2s.map(from)).map { x =>
        (x._1.map(to), x._2, x._3)
      }
    }

  def xmapState[S2](update: (S2, S) => S2)(extract: S2 => S)(implicit F: Functor[F]): ActionT[F, C, S2, E, A] =
    ActionT { (c2, s2, u2, es) =>
      unsafeRun(c2, extract(s2), (s, e) => extract(u2(update(s2, s), e)), es).map {
        case (e, sN, a) => (e, update(s2, sN), a)
      }
    }

  def product[B](that: ActionT[F, C, S, E, B])(implicit F: Monad[F]): ActionT[F, C, S, E, (A, B)] =
    flatMap(a => that.map(b => (a, b)))

  def mapK[G[_]](m: F ~> G): ActionT[G, C, S, E, A] =
    ActionT { (c, s, u, es) =>
      m(unsafeRun(c, s, u, es))
    }
}

object ActionT extends ActionTFunctions with ActionTInstances {

  private[akkart] def apply[F[_], C, S, E, A](
    unsafeRun: (KeyedCtx[C], S, (S, E) => S, Chain[E]) => F[(Chain[E], S, A)]
  ): ActionT[F, C, S, E, A] = new ActionT(unsafeRun)
}

trait ActionTFunctions {

  def read[F[_]: Applicative, C, S, E]: ActionT[F, C, S, E, S] =
    ActionT((_, s, _, es) => (es, s, s).pure[F])

  def set[F[_]: Applicative, C, S, E](s0: S): ActionT[F, C, S, E, Unit] =
    ActionT((_, _, _, es) => (es, s0, ()).pure[F])

  def modify[F[_]: Applicative, C, S, E](func: S => S): ActionT[F, C, S, E, Unit] =
    ActionT((_, s, _, es) => (es, func(s), ()).pure[F])

  def ctx[F[_]: Applicative, C, S, E]: ActionT[F, C, S, E, KeyedCtx[C]]                  =
    ActionT((c, s, _, es) => (es, s, c.copy(ctx = c.ctx.copy(lastSequenceNr = c.ctx.lastSequenceNr + es.size))).pure[F])

  def append[F[_]: Applicative, C, S, E](e: NonEmptyChain[E]): ActionT[F, C, S, E, Unit] =
    ActionT((_, s, u, es0) => (es0 ++ e.toChain, e.foldl(s)(u), ()).pure[F])

  def reset[F[_]: Applicative, C, S, E]: ActionT[F, C, S, E, Unit] =
    ActionT((_, s, _, _) => (Chain.empty[E], s, ()).pure[F])

  def liftF[F[_]: Functor, C, S, E, A](fa: F[A]): ActionT[F, C, S, E, A] =
    ActionT((_, s, _, es) => fa.map(a => (es, s, a)))

  def pure[F[_]: Applicative, C, S, E, A](a: A): ActionT[F, C, S, E, A] =
    ActionT((_, s, _, es) => (es, s, a).pure[F])

  def sample[F[_]: Monad, C, S, E1, Env, E2](
    getEnv: F[Env]
  )(
    update: (Env, E1) => E2
  )(
    extract: E2 => E1
  ): ActionT[F, C, S, E1, ?] ~> ActionT[F, C, S, E2, ?] =
    new (ActionT[F, C, S, E1, ?] ~> ActionT[F, C, S, E2, ?]) {

      override def apply[A](fa: ActionT[F, C, S, E1, A]): ActionT[F, C, S, E2, A] =
        fa.sample(getEnv)(update)(extract)
    }

  def xmapEvents[F[_]: Functor, C, S, E1, E2, R](
    to: E1 => E2,
    from: E2 => E1
  ): ActionT[F, C, S, E1, ?] ~> ActionT[F, C, S, E2, ?] =
    new (ActionT[F, C, S, E1, ?] ~> ActionT[F, C, S, E2, ?]) {

      override def apply[A](fa: ActionT[F, C, S, E1, A]): ActionT[F, C, S, E2, A] =
        fa.xmapEvents(to, from)
    }

  def xmapState[F[_]: Functor, C, S1, S2, E](
    update: (S2, S1) => S2
  )(
    extract: S2 => S1
  ): ActionT[F, C, S1, E, ?] ~> ActionT[F, C, S2, E, ?] =
    new (ActionT[F, C, S1, E, ?] ~> ActionT[F, C, S2, E, ?]) {

      override def apply[A](fa: ActionT[F, C, S1, E, A]): ActionT[F, C, S2, E, A] =
        fa.xmapState(update)(extract)
    }
}

trait ActionTInstances extends ActionTLowerPriorityInstances1 {

  implicit def monadActionLiftRejectInstance[F[_], C, S, E, R](
    implicit F0: MonadError[F, R]
  ): MonadActionLiftReject[ActionT[F, C, S, E, ?], F, C, S, E, R] =
    new MonadActionLiftReject[ActionT[F, C, S, E, ?], F, C, S, E, R] with ActionTMonadActionLiftInstance[F, C, S, E] {
      override protected implicit def F: Monad[F]          = F0
      override def reject[A](r: R): ActionT[F, C, S, E, A] = ActionT.liftF(F0.raiseError[A](r))

    }

  implicit def actionTFunctorKInstance[C, S, E, A]: FunctorK[ActionT[?[_], C, S, E, A]] =
    new FunctorK[ActionT[?[_], C, S, E, A]] {
      def mapK[F[_], G[_]](a: ActionT[F, C, S, E, A])(f: ~>[F, G]): ActionT[G, C, S, E, A] = a.mapK(f)
    }
}

trait ActionTLowerPriorityInstances1 {

  trait ActionTMonadActionLiftInstance[F[_], C, S, E] extends MonadActionLift[ActionT[F, C, S, E, ?], F, C, S, E] {
    protected implicit def F: Monad[F]

    override def modify(fun: S => S): ActionT[F, C, S, E, Unit] = ActionT.modify(fun)

    override def set(s: S): ActionT[F, C, S, E, Unit]  = ActionT.set(s)
    override def ctx: ActionT[F, C, S, E, KeyedCtx[C]] = ActionT.ctx
    override def read: ActionT[F, C, S, E, S]          = ActionT.read

    override def append(e: E, es: E*): ActionT[F, C, S, E, Unit] =
      ActionT.append(NonEmptyChain(e, es: _*))
    override def reset: ActionT[F, C, S, E, Unit]                = ActionT.reset

    override def map[A, B](fa: ActionT[F, C, S, E, A])(f: A => B): ActionT[F, C, S, E, B] =
      fa.map(f)

    override def flatMap[A, B](fa: ActionT[F, C, S, E, A])(f: A => ActionT[F, C, S, E, B]): ActionT[F, C, S, E, B] =
      fa.flatMap(f)

    override def tailRecM[A, B](a: A)(f: A => ActionT[F, C, S, E, Either[A, B]]): ActionT[F, C, S, E, B] =
      ActionT { (c0, s, ue, es) =>
        F.tailRecM(a) { a =>
          f(a).unsafeRun(c0, s, ue, es).flatMap[Either[A, (Chain[E], S, B)]] {
            case (es2, s, Right(b)) => (es2, s, b).asRight[A].pure[F]
            case (es2, s, Left(na)) =>
              es2 match {
                case c if c.nonEmpty =>
                  ActionT
                    .append[F, C, S, E](NonEmptyChain.fromChainUnsafe(c))
                    .unsafeRun(c0, s, ue, es)
                    .as(na.asLeft[(Chain[E], S, B)])
                case _               =>
                  na.asLeft[(Chain[E], S, B)].pure[F]
              }
          }
        }
      }
    override def pure[A](x: A): ActionT[F, C, S, E, A]                                                   = ActionT.pure(x)
    override def liftF[A](fa: F[A]): ActionT[F, C, S, E, A]                                              = ActionT.liftF(fa)
  }

  implicit def monadActionLiftInstance[F[_], C, S, E](
    implicit F0: Monad[F]
  ): MonadActionLift[ActionT[F, C, S, E, ?], F, C, S, E] =
    new ActionTMonadActionLiftInstance[F, C, S, E] {
      override protected implicit def F: Monad[F] = F0
    }

}
