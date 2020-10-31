package zio.zproc

import java.util.UUID

import zio.zproc.ZProc._
import zio.zproc.internal.JournalEvent._
import zio.ZIO.ifM
import zio.clock.Clock
import zio.duration.Duration
import zio.{Has, UIO, ZIO}

abstract class ZProc[-R, +E, +St, +A] { self =>

  def map[B](fn: A => B): ZProc[R, E, St, B] = MapFn(this, fn)

  def flatMap[R1 <: R, E1 >: E, St1 >: St, B](k: A => ZProc[R1, E1, St1, B]): ZProc[R1, E1, St1, B] =
    FlatMap(this, k)

  def transact[St1 >: St](ctx: Ctx[St1]): ZIO[R, E, A] = ZProc.transact[R, E, St1, A](this)(ctx)

  def set[St1 >: St](st: St1): ZProc[R, E, St1, Unit] = SetState(st)

  def mapState[St1 >: St](fn: St1 => St1): ZProc[R, E, St1, Unit] = MapState(fn)

  def unit: ZProc[R, E, St, Unit] = map(_ => ())

//  protected def withInst(i: Int): this.type
}

object ZProc {

  private[zproc] case class FlatMap[R, E, St, A, A0](r: ZProc[R, E, St, A], k: A => ZProc[R, E, St, A0])
      extends ZProc[R, E, St, A0]

  private[zproc] case class MapFn[R, E, St, A, A0](r: ZProc[R, E, St, A], k: A => A0) extends ZProc[R, E, St, A0]

  private[zproc] case class Pure[R, E, St, A](pure: A) extends ZProc[R, E, St, A]

  private[zproc] case class Await[R, E, St](condition: St => ZIO[R, E, Boolean]) extends ZProc[R, E, St, Unit]

  private[zproc] case class Lift[R, E, St, A](z: ZIO[R, E, A]) extends ZProc[R, E, St, A]

  private[zproc] case class MapState[R, E, St](fn: St => St) extends ZProc[R, E, St, Unit]

  private[zproc] case class SetState[R, E, St](st: St) extends ZProc[R, E, St, Unit]

  private[zproc] case class Delay[R, St](duration: Duration) extends ZProc[R, Nothing, St, Unit]

  def pure[St, A](pure: A): ZProc[Any, Nothing, St, A] = Pure(pure)

  def lift[R, E, A](z: ZIO[R, E, A]): ZProc[R, E, Nothing, A] = Lift(z)

  def await[St](condition: St => ZIO[Any, Nothing, Boolean]): ZProc[Any, Nothing, St, Unit] = Await(condition)

  def mapState[R, E, St](fn: St => St): ZProc[R, E, St, Unit] = MapState(fn)

  def set[St](fn: St): ZProc[Any, Nothing, St, Unit] = SetState(fn)

  def delay[R, St](d: Duration): ZProc[R, Nothing, St, Unit] = Delay(d)

  def transact[R, E, St, A](proc: ZProc[R, E, St, A])(ctx: Ctx[St]): ZIO[R, E, A] = {
    def pullQueue(
      ctx: Ctx[St],
      eventId: Long,
      awaitingEventId: Long,
      condition: St => ZIO[R, E, Boolean]
    ): ZIO[R, E, Unit] = {
      ctx.inboundQueue.take.flatMap[R, E, Unit] { st =>
        ifM(condition(st))(
          ctx.logEvent(AwaitConditionSucceeded(eventId, awaitingEventId)),
          pullQueue(ctx, eventId, awaitingEventId, condition)
        ).unit
      }
    }

    proc match {
      case FlatMap(r, k)          =>
        //      r.transact(ctx).flatMap(f => k(f).transact(ctx))

        transact(r)(ctx).flatMap { f =>
          transact(k(f))(ctx)
        }
      case Pure(pure)             =>
        ZIO.succeed(pure)

      case MapFn(r, k)            =>
        transact(r)(ctx).map(k)
      //      r.transact(ctx).map(k)

      case Lift(z)                =>
        z

      case await: Await[R, E, St] =>
        ctx.processCursor.zip(ctx.persistedSeqNr).flatMap {
          case (cursor, persistedSeqNr) =>
            val numEvents = 2

            if (persistedSeqNr > cursor + numEvents) {
              ctx.incAndEventId(numEvents).unit
            } else {

              if (persistedSeqNr == cursor)
                for {
                  _  <- ctx.logEvent(AwaitConditionSync(persistedSeqNr + 1))
                  c  <- ctx.workflowContext.get
                  _  <- pullQueue(ctx, c.persistedSeqNr + 1, c.persistedSeqNr, await.condition).fork
                  st <- ctx.state.get
                  _  <- ctx.inboundQueue.offer(st)
                  _  <- ctx.waitCondition

                } yield ()
              else {

                for {
                  c  <- ctx.workflowContext.get
                  _  <- pullQueue(ctx, c.persistedSeqNr + 1, c.persistedSeqNr, await.condition).fork
                  st <- ctx.state.get
                  _  <- ctx.inboundQueue.offer(st)
                  _  <- ctx.waitCondition

                } yield ()
              }
            }

        }

      case m: MapState[R, E, St]  =>
        ctx.modify(m.fn).flatMap { newCtx =>
          newCtx.state.get.flatMap { state =>
            ctx.inboundQueue.offer(state).unit
          }
        }

      case s: SetState[R, E, St]  =>
        ctx.state.set(s.st) *> ctx.state.get.flatMap { state =>
          ctx.inboundQueue.offer(state).unit
        }

      case Delay(duration)        =>
        ctx.processCursor.zip(ctx.persistedSeqNr).flatMap {
          case (eventId, persistedSeqNr) =>
            val numEvents = 2

            if (persistedSeqNr > eventId + numEvents) {
              ctx.incAndEventId(numEvents).unit
            } else {
              if (persistedSeqNr == eventId)
                for {
                  now <- ctx.instant
                  _   <- ctx.logEvent(DelayConditionSync(persistedSeqNr + 1, duration, now))
                  c   <- ctx.workflowContext.get
                  _   <- (ZIO.unit
                      .delay(duration)
                      .provide(Has(ctx.clock))
                      *> ctx.logEvent(DelayConditionSucceeded(c.persistedSeqNr + 1, c.persistedSeqNr))).fork
                  _   <- ctx.waitCondition

                } yield ()
              else {
                for {
                  c            <- ctx.workflowContext.get
                  now          <- ctx.instant
                  durationLeft <- ctx.getOpenState.flatMap {
                    case Some(DelayConditionSync(_, duration, startTime)) =>
                      ZIO.succeed(
                        Duration.fromMillis(startTime.plusMillis(duration.toMillis).toEpochMilli - now.toEpochMilli)
                      )
                    case _                                                => ZIO.dieMessage("Unexpected state encountered")
                  }
                  _            <- (ZIO.unit
                      .delay(durationLeft)
                      .provide(Has(ctx.clock))
                      *> ctx.logEvent(DelayConditionSucceeded(c.persistedSeqNr + 1, c.persistedSeqNr))).fork
                  _            <- ctx.waitCondition

                } yield ()
              }
            }
        }

    }
  }
}
