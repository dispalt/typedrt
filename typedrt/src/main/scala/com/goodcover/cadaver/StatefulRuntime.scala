package com.goodcover.cadaver

import cats.~>
import com.goodcover.cadaver.internal.{JournalEvent, ProcessContext}
import zio._
import zio.clock.Clock

object StatefulRuntime {

  abstract class SignalHandler[R, E, St, Sig[_, _, _, _], F[_]] extends (Sig[R, E, St, *] ~> F) {}

  abstract class SignalClientFn[R, E, St, Sig[_, _, _, _]] extends SignalHandler[R, E, St, Sig, ZIO[R, E, *]] {
    def fiber: Fiber.Runtime[E, Unit]

  }

  type SignalServerFn[R, E, St, Sig[_, _, _, _]] =
    SignalHandler[R, E, St, Sig, ZProc[R, E, St, *]]

  private def mkCtx[St](
    theClock: Clock.Service,
    initialState: Ref[St],
    pc: Ref[ProcessContext],
    iq: Queue[St]
  ): Ctx[St] =
    new Ctx[St] {
      override def state: Ref[St] = initialState

      override def clock: Clock.Service = theClock

      override def workflowContext: Ref[ProcessContext] = pc

      override def inboundQueue: Queue[St] = iq
    }

  //

  def deploy[R <: Clock, E, St, K, Sig[_, _, _, _]](
    id: K,
    initialState: St,
    proc: ZProc[R, E, St, Unit],
    signalHandler: SignalServerFn[R, E, St, Sig]
  ): ZIO[R, E, SignalClientFn[R, E, St, Sig]] = {

    for {
      clock        <- ZIO.service[Clock.Service]
      q            <- Queue.unbounded[St]
      ref          <- Ref.make(ProcessContext(Chunk[JournalEvent](), 0, 0, None))
      initialState <- Ref.make(initialState)
      ctx = mkCtx(clock, initialState, ref, q)

      f <- ZProc.transact(proc)(ctx).fork
    } yield {
      new SignalClientFn[R, E, St, Sig] {
        override def apply[A](fa: Sig[R, E, St, A]): ZIO[R, E, A] = {
          ZProc.transact(signalHandler(fa))(ctx)
        }

        override def fiber: Fiber.Runtime[E, Unit] = f
      }
    }
  }
}
