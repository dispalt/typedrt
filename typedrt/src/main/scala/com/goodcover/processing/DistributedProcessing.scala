package com.goodcover.processing

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import akka.Done
import cats.implicits._
import com.goodcover.processing.DistributedProcessing.{Process, ProcessKillSwitch}
import com.goodcover.fxn._
import akka.actor.{ActorSystem, CoordinatedShutdown, SupervisorStrategy}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.pattern.{ask, BackoffOpts, BackoffSupervisor}
import akka.util.Timeout
import cats.effect.Effect
import com.goodcover.processing.DistributedProcessingSupervisor.KeepRunning

import scala.collection.immutable._
import scala.concurrent.duration.{FiniteDuration, _}

class DistributedProcessing(system: ActorSystem) {

  def start[F[_]](
    name: String,
    processes: Seq[Process[F]],
    settings: DistributedProcessingSettings = DistributedProcessingSettings(system)
  )(
    implicit F: Effect[F]
  ): F[ProcessKillSwitch[F]] =
    F.delay {

      val props = BackoffSupervisor.props(
        BackoffOpts.onFailure(
          DistributedProcessingWorker.props(processes, name),
          "worker",
          settings.minBackoff,
          settings.maxBackoff,
          settings.randomFactor
        )
      )

      val region = ClusterSharding(system).start(
        typeName        = name,
        entityProps     = props,
        settings        = settings.clusterShardingSettings,
        extractEntityId = {
          case c @ KeepRunning(workerId) => (workerId.toString, c)
        },
        extractShardId  = {
          case KeepRunning(workerId) => (workerId % settings.numberOfShards).toString
          case other                 => throw new IllegalArgumentException(s"Unexpected message [$other]")
        }
      )

      val regionSupervisor = system.actorOf(
        DistributedProcessingSupervisor.props(processes.size, region, settings.heartbeatInterval),
        "DistributedProcessingSupervisor-" + URLEncoder.encode(name, StandardCharsets.UTF_8.name())
      )

      implicit val timeout: Timeout = Timeout(settings.shutdownTimeout)

      CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceUnbind, s"distributed-processing-$name") {
        () =>
          regionSupervisor.?(DistributedProcessingSupervisor.GracefulShutdown).map(_ => Done)(system.dispatcher)
      }

      ProcessKillSwitch {
        F.fromFuture {
          regionSupervisor.?(DistributedProcessingSupervisor.GracefulShutdown)
        }.map(_ => ())
      }
    }

}

object DistributedProcessing {
  def apply(system: ActorSystem): DistributedProcessing = new DistributedProcessing(system)
  final case class ProcessKillSwitch[F[_]](shutdown: F[Unit])
  final case class RunningProcess[F[_]](watchTermination: F[Unit], shutdown: () => Unit)
  final case class Process[F[_]](run: F[RunningProcess[F]])
}

final case class DistributedProcessingSettings(
  minBackoff: FiniteDuration,
  maxBackoff: FiniteDuration,
  randomFactor: Double,
  shutdownTimeout: FiniteDuration,
  numberOfShards: Int,
  heartbeatInterval: FiniteDuration,
  clusterShardingSettings: ClusterShardingSettings)

object DistributedProcessingSettings {

  def apply(system: ActorSystem): DistributedProcessingSettings =
    DistributedProcessingSettings(
      minBackoff              = 3.seconds,
      maxBackoff              = 10.seconds,
      randomFactor            = 0.2,
      shutdownTimeout         = 10.seconds,
      numberOfShards          = 100,
      heartbeatInterval       = 2.seconds,
      clusterShardingSettings = ClusterShardingSettings(system)
    )
}
