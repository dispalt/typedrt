package com.goodcover.akkart

import cats.data.{Chain, EitherT}
import cats.{~>, Monad}
import cats.tagless.FunctorK
import cats.tagless.syntax.functorK._
import com.dispalt.tagless.util.WireProtocol.Invocation

final case class BehaviorM[M[_[_]], F[_], C, S, E](
  actions: M[ActionT[F, C, S, E, ?]],
  create: C => S,
  update: (S, E) => S,
  recoveryComplete: S => S = (s: S) => s) {

  def enrich[Env](f: F[Env])(implicit M: FunctorK[M], F: Monad[F]): BehaviorM[M, F, C, S, Enriched[Env, E]] =
    BehaviorM(
      actions.mapK(ActionT.sample[F, C, S, E, Env, Enriched[Env, E]](f)(Enriched(_, _))(_.event)),
      create,
      (s, e) => update(s, e.event),
      recoveryComplete
    )

  def mapK[G[_]](m: F ~> G)(implicit M: FunctorK[M]): BehaviorM[M, G, C, S, E] = {
    copy(actions.mapK(λ[ActionT[F, C, S, E, ?] ~> ActionT[G, C, S, E, ?]](_.mapK(m))))
  }

  def run[A](ctx: KeyedCtx[C], state: S, invocation: Invocation[M, A]): F[(Chain[E], S, A)] =
    invocation
      .run(actions)
      .run(ctx, state, update)
}

final case class Enriched[M, E](metadata: M, event: E)

object BehaviorM extends BehaviorMInstances {

  def pure[M[_[_]], F[_], C, S, E](
    actions: M[ActionT[F, C, S, E, ?]],
    create: S,
    update: (S, E) => S,
    recoveryComplete: S => S = (s: S) => s
  ): BehaviorM[M, F, C, S, E] =
    new BehaviorM[M, F, C, S, E](actions, (_: C) => create, update, recoveryComplete)

  def rejectable[M[_[_]], F[_], C, State, Event, Rejection](
    actions: M[EitherT[ActionT[F, C, State, Event, ?], Rejection, ?]],
    initial: C => State,
    update: (State, Event) => State,
    recoveryComplete: State => State = (s: State) => s,
  ): BehaviorM[EitherKT[M, Rejection, ?[_]], F, C, State, Event] =
    BehaviorM(EitherKT(actions), initial, (s, e) => update(s, e), recoveryComplete)

}

trait BehaviorMInstances {

  implicit def behaviourMFunctorKInstance[M[_[_]]: FunctorK, C, S, E]: FunctorK[BehaviorM[M, ?[_], C, S, E]] =
    new FunctorK[BehaviorM[M, ?[_], C, S, E]] {
      def mapK[F[_], G[_]](a: BehaviorM[M, F, C, S, E])(f: F ~> G): BehaviorM[M, G, C, S, E] = a.mapK(f)
    }
}
