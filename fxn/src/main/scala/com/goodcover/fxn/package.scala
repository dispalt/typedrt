package com.goodcover

import cats.effect.{Effect, IO, LiftIO}
import monix.eval.Task
import monix.execution.Scheduler

import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}
import scala.util.{Left, Right}

package object fxn {

  implicit class RunningF[F[_], A](private val self: F[A]) {

    @inline final def unsafeToFuture()(implicit F: Effect[F]): Future[A] = {
      val p = Promise[A]()
      F.runAsync(self) {
        case Right(a) =>
          IO {
            p.success(a); ()
          }
        case Left(e)  =>
          IO {
            p.failure(e); ()
          }
      }.unsafeRunSync()
      p.future
    }

    final def liftR[R[_]](implicit F: Effect[F], R: LiftIO[R]): R[A] = {
      F.toIO(self).to[R]
    }
  }

  final implicit class RunningFIOOps[F[_]](private val self: LiftIO[F]) extends AnyVal {

    def fromFuture[A](future: => Future[A]): F[A] =
      IO.fromFuture(IO(future))(IO.contextShift(scala.concurrent.ExecutionContext.global)).to(self)

    def fromTask[A](task: Task[A])(implicit ec: Scheduler): F[A] = {
      self.liftIO(task.to[IO])
    }
  }

  def orderedGroupBy[T, P](seq: Iterable[T])(f: T => P): Seq[(P, Iterable[T])] = {
    @tailrec
    def accumulator(seq: Iterable[T], f: T => P, res: List[(P, Iterable[T])]): Seq[(P, Iterable[T])] =
      seq.headOption match {
        case None    => res.reverse
        case Some(h) =>
          val key    = f(h)
          val subseq = seq.takeWhile(f(_) == key)
          accumulator(seq.drop(subseq.size), f, (key -> subseq) :: res)

      }
    accumulator(seq, f, Nil)
  }
}
