package com.goodcover.typedrt

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{KillSwitches, Materializer}
import cats.effect.Async
import cats.syntax.functor._
import com.goodcover.fxn._
import com.goodcover.processing.DistributedProcessing.{Process, RunningProcess}

object AkkaStreamProcess {

  final class Builder[F[_]] {

    def apply[M](source: Source[Unit, M])(implicit F: Async[F], materializer: Materializer): Process[F] =
      Process(run = F.delay {
        val (killSwitch, terminated) = source
          .viaMat(KillSwitches.single)(Keep.right)
          .toMat(Sink.ignore)(Keep.both)
          .run()
        RunningProcess(F.fromFuture(terminated).void, () => killSwitch.shutdown())
      })
  }
  def apply[F[_]]: Builder[F] = new Builder[F]
}
