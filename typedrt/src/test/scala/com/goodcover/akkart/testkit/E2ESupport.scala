package com.goodcover.akkart.testkit

import cats.data.{EitherT, StateT}
import cats.effect.{Async, Effect, ExitCase, IO, Sync, SyncIO}
import cats.implicits._
import cats.mtl.Stateful
import cats.tagless.FunctorK
import cats.tagless.syntax.functorK._
import cats._
import cats.effect.concurrent.Ref
import com.dispalt.tagless.util.WireProtocol
import com.dispalt.tagless.util.WireProtocol.{Encoded, Invocation}
import com.goodcover.akkart.read.EntityEvent
import com.goodcover.akkart.{testkit, BehaviorM}
import com.goodcover.typedrt.data.{PairT, Tagging}
import monocle.Lens

import scala.collection.immutable._

object E2ESupport {

  final def behavior[M[_[_]]: FunctorK, F[_]: Sync, S, E, K](
    behavior: BehaviorM[M, F, K, S, E],
    journal: EventJournal[F, K, E]
  ): K => F[M[F]] =
    Eventsourced[M, F, S, E, K](behavior, journal)

  trait ReifiedInvocations[M[_[_]]] {
    def invocations: M[Invocation[M, ?]]
  }

  object ReifiedInvocations {
    def apply[M[_[_]]](implicit M: ReifiedInvocations[M]): ReifiedInvocations[M] = M
  }

  final class Runtime[F[_]] {

    def deploy[K, M[_[_]]: FunctorK](
      load: K => F[M[F]]
    )(
      implicit F: MonadError[F, Throwable],
      M: WireProtocol[M]
    ): K => M[F] = { key: K =>
      M.encoder.mapK {
        new (Encoded ~> F) {
          override final def apply[A](encoded: Encoded[A]): F[A] =
            for {
              mf   <- load(key)
              pair <- M.decoder.apply(encoded._1).liftTo[F]
              a    <-
                pair.first
                  .run(mf)
                  .flatMap(ea => pair.second.apply(ea).pure[F])
                  .flatMap(b => encoded._2.apply(b).liftTo[F])
            } yield a
        }
      }
    }
  }

  abstract class Processes[F[_]](_items: => Vector[F[Unit]]) {
    protected type S
    protected implicit def F: Stateful[F, S]

    final private implicit val monad = F.monad

    lazy val items = _items

    final def runProcesses: F[Unit] =
      for {
        stateBefore <- F.get
        _           <- items.sequence
        stateAfter  <- F.get
        _           <-
          if (stateAfter == stateBefore) {
            ().pure[F]
          } else {
            runProcesses
          }
      } yield ()

    final def wiredK[I, M[_[_]]](behavior: I => M[F])(implicit M: FunctorK[M]): I => M[F] =
      i =>
        behavior(i).mapK(new (F ~> F) {

          override def apply[A](fa: F[A]): F[A] =
            fa <* runProcesses
        })

    final def wired[A, B](f: A => F[B]): A => F[B] =
      f.andThen(_ <* runProcesses)
  }

  object Processes {

    def apply[F[_], S0](items: => Vector[F[Unit]])(implicit F0: Stateful[F, S0]): Processes[F] =
      new Processes[F](items) {
        override final type S = S0
        override implicit def F: Stateful[F, S] = F0
      }
  }

  class RefStateful[F[_]: Monad, S](ref: Ref[F, S]) extends Stateful[F, S] {
    val monad: Monad[F]                      = implicitly
    def get: F[S]                            = ref.get
    def set(s: S): F[Unit]                   = ref.set(s)
    override def inspect[A](f: S => A): F[A] = ref.get.map(f)
    override def modify(f: S => S): F[Unit]  = ref.update(f)
  }
}

trait E2ESupport {

  type SpecState

  type F[A] = IO[A]
  def F[A](a: A): F[A] = IO(a)

  def withRef[B](ss: SpecState)(f: Ref[F, SpecState] => Stateful[F, SpecState] => B): B = {
    val r  = Ref.unsafe[F, SpecState](ss)
    val ms = new E2ESupport.RefStateful(r)
    f(r)(ms)
  }

  // This frankly just makes testing a lot easier.  you've got full control over resetting and you also have the implicit,
  // which you can reset, which you really shouldn't but you can if you want
  def newRef(ss: SpecState): (Ref[F, SpecState], Stateful[F, SpecState]) = {
    val r  = Ref.unsafe[F, SpecState](ss)
    val ms = new E2ESupport.RefStateful(r)
    (r, ms)
  }

  def refStateful[B](ss: SpecState): E2ESupport.RefStateful[F, SpecState] =
    new E2ESupport.RefStateful[F, SpecState](Ref.unsafe[F, SpecState](ss))

  final def mkJournal[I, E](
    lens: Lens[SpecState, StateEventJournal.State[I, E]],
    tagging: Tagging[I],
  )(
    implicit MS: Stateful[F, SpecState]
  ): StateEventJournal[F, I, SpecState, E] = {
    StateEventJournal[F, I, SpecState, E](lens, tagging)
  }

  final def wireProcess[In](
    process: In => F[Unit],
    source: Processable[F, In],
    sources: Processable[F, In]*
  )(
    implicit F: Monad[F]
  ): F[Unit] =
    sources
      .fold(source)(_ merge _)
      .process(process)
      .void

  final def wireProcessSimple[K, In](
    process: In => F[Unit],
    source: Processable[F, EntityEvent[K, In]],
    sources: Processable[F, EntityEvent[K, In]]*
  )(
    implicit F: Monad[F]
  ): F[Unit] =
    sources
      .fold(source)(_ merge _)
      .process(ee => process(ee.ev))
      .void

  val runtime = new E2ESupport.Runtime[F]

}
