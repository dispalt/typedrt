package com.goodcover.akkart.schedule.process

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source}
import cats.effect.Effect
import com.goodcover.processing.{DistributedProcessing, DistributedProcessingSettings}
import com.goodcover.processing.DistributedProcessing.ProcessKillSwitch
import com.goodcover.typedrt.AkkaStreamProcess
import com.goodcover.fxn._

import scala.collection.immutable._
import scala.concurrent.duration.{FiniteDuration, _}

object PeriodicProcessRuntime {

  def apply[F[_]: Effect](
    name: String,
    tickInterval: FiniteDuration,
    processCycle: F[Unit]
  )(
    implicit materializer: Materializer
  ): PeriodicProcessRuntime[F] =
    new PeriodicProcessRuntime(name, tickInterval, processCycle)
}

class PeriodicProcessRuntime[F[_]: Effect](
  name: String,
  tickInterval: FiniteDuration,
  processCycle: F[Unit]
)(
  implicit materializer: Materializer) {

  private def source =
    Source
      .tick(0.seconds, tickInterval, processCycle)
      .mapAsync(1)(_.unsafeToFuture())
      .mapMaterializedValue(_ => NotUsed)

  def run(system: ActorSystem, settings: Option[DistributedProcessingSettings] = None): F[ProcessKillSwitch[F]] =
    DistributedProcessing(system)
      .start(
        s"$name-Process",
        List(AkkaStreamProcess[F](source)),
        settings.getOrElse(DistributedProcessingSettings(system))
      )

}
