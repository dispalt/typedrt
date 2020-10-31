package com.goodcover.processing

import com.goodcover.processing.DistributedProcessing._
import com.goodcover.fxn._
import akka.actor.{Actor, ActorLogging, Props, Status}
import akka.pattern._
import cats.effect.Effect
import cats.effect.syntax.effect._
import cats.implicits._
import com.goodcover.processing.DistributedProcessingSupervisor.KeepRunning

private[processing] object DistributedProcessingWorker {

  def props[F[_]: Effect](processWithId: Int => Process[F], processName: String): Props =
    Props(new DistributedProcessingWorker(processWithId, processName))

}

private[processing] class DistributedProcessingWorker[F[_]: Effect](processFor: Int => Process[F], processName: String)
    extends Actor
    with ActorLogging {

  case class ProcessStarted(process: RunningProcess[F])
  case object ProcessTerminated
  import context.dispatcher

  var killSwitch: Option[() => Unit] = None

  override def postStop(): Unit =
    killSwitch.foreach(_.apply())

  def receive: Receive = {
    case KeepRunning(workerId) =>
      log.debug("[{}] Starting process {}", workerId, processName)
      processFor(workerId).run.map(ProcessStarted).toIO.unsafeToFuture() pipeTo self
      context.become {
        case ProcessStarted(RunningProcess(watchTermination, terminate)) =>
          log.debug("[{}] Process started {}", workerId, processName)
          killSwitch = Some(terminate)
          watchTermination.toIO.map(_ => ProcessTerminated).unsafeToFuture() pipeTo self
          context.become {
            case Status.Failure(e) =>
              log.error(e, "Process failed {}", processName)
              throw e

            case ProcessTerminated =>
              log.error("Process terminated {}", processName)
              throw new IllegalStateException(s"Process terminated $processName")

            case KeepRunning(_)    => ()
          }
        case Status.Failure(e)                                           =>
          log.error(e, "Process failed to start {}", processName)
          throw e
        case KeepRunning(_)                                              => ()
      }
  }
}
