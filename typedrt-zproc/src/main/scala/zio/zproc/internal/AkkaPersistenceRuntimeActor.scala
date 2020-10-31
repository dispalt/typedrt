package zio.zproc.internal

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import java.util.UUID

import akka.actor.{ActorLogging, Props, ReceiveTimeout, Stash, Status}
import akka.cluster.sharding.ShardRegion
import akka.persistence.journal.Tagged
import akka.persistence._
import cats.data.Chain
import cats.effect.Effect
import cats.implicits._
import com.dispalt.tagless.util.WireProtocol
import com.dispalt.tagless.util.WireProtocol.{Encoder, Invocation}
import zio.zproc.internal.AkkaPersistenceRuntimeActor.{CommandResult, HandleCommand, WrappedCause}
import zio.zproc.internal.SnapshotPolicy.{EachNumberOfEvents, Never}
import com.goodcover.akkart.msg.PersistRepr
import com.goodcover.akkart.serialization.PersistCodec
import zio.zproc.ZProc
import com.goodcover.encoding.KeyCodec
import com.goodcover.typedrt.data.Tagging
import com.goodcover.typedrt.serialization.MarkerMessage
import com.google.protobuf.ByteString
import zio.{Cause, Exit}

import scala.concurrent.duration.FiniteDuration
import scala.util._
import scala.util.control.NoStackTrace

sealed abstract class SnapshotPolicy[+E] extends Product with Serializable {

  def pluginId: String = this match {
    case Never                                      => ""
    case EachNumberOfEvents(_, _, snapshotPluginId) => snapshotPluginId
  }
}

object SnapshotPolicy {
  def never[E]: SnapshotPolicy[E] = Never.asInstanceOf[SnapshotPolicy[E]]

  def eachNumberOfEvents[E: PersistCodec](numberOfEvents: Int, keepN: Int, pluginId: String = ""): SnapshotPolicy[E] =
    EachNumberOfEvents(numberOfEvents, keepN, pluginId)

  private[internal] case object Never extends SnapshotPolicy[Nothing]

  final private[internal] case class EachNumberOfEvents[State: PersistCodec](
    numberOfEvents: Int,
    keepN: Int,
    snapshotPluginId: String)
      extends SnapshotPolicy[State] {
    def encode(state: State): PersistRepr     = PersistCodec[State].encodeWrapper(state)
    def decode(repr: PersistRepr): Try[State] = PersistCodec[State].decodeWrapper(repr)
  }

}

object AkkaPersistenceRuntimeActor {

//  def props[M[_[_]], F[_]: Effect, I: KeyCodec, State, Event: PersistCodec](
//    entityName: String,
//    actions: M[ActionT[F, I, State, Event, *]],
//    initialState: I => State,
//    updateState: (State, Event) => State,
//    snapshotPolicy: SnapshotPolicy[State],
//    tagging: Tagging[I],
//    idleTimeout: FiniteDuration,
//    recovery: Recovery,
//    journalPluginId: String,
//    snapshotPluginId: String,
//    recoverCompleted: State => State,
//  )(
//    implicit M: WireProtocol[M]
//  ): Props =
//    Props(
//      new AkkaPersistenceRuntimeActor(
//        entityName,
//        actions,
//        initialState,
//        updateState,
//        snapshotPolicy,
//        tagging,
//        idleTimeout,
//        recovery,
//        journalPluginId,
//        snapshotPluginId,
//        recoverCompleted,
//      )
//    )

  final case class HandleCommand(commandBytes: ByteString) extends MarkerMessage
  final case class CommandResult(resultBytes: ByteString)  extends MarkerMessage
  case object Stop

  val PersistenceIdSeparator: String = "-"

  def combineEncodedId(entityName: String, id: String) =
    s"$entityName$PersistenceIdSeparator$id"

  def combineId(entityName: String, id: String): String =
    combineEncodedId(entityName, URLDecoder.decode(id, StandardCharsets.UTF_8.name()))

  case class WrappedCause[+E](cause: Cause[E]) extends NoStackTrace {
    override def toString: String = cause.toString
  }
}

/**
  * Actor encapsulating state of event sourced entity behavior [Behavior]
  *
  * @param entityName entity name used as persistence prefix and as a tag for all events
  * @param snapshotPolicy snapshot policy to use
  * @param idleTimeout - time with no commands after which graceful actor shutdown is initiated
  */
final class AkkaPersistenceRuntimeActor[M[_[_]], -R, +Err, I: KeyCodec, State, Event] private[internal] (
  entityName: String,
  actions: ZProc[R, Err, I, Unit],
  initialState: I => State,
  updateState: (State, Event) => State,
  snapshotPolicy: SnapshotPolicy[State],
  tagger: Tagging[I],
  idleTimeout: FiniteDuration,
  override val recovery: Recovery,
  override val journalPluginId: String,
  override val snapshotPluginId: String,
  recoverCompleted: State => State,
)(
  implicit val serDe: PersistCodec[Event],
  M: WireProtocol[M])
    extends PersistentActor
    with ActorLogging
    with Stash {

  private case class ActionResult(opId: UUID, events: Chain[Event], s: State, resultBytes: Array[Byte])

  private val entityId: String =
    URLDecoder.decode(self.path.name, StandardCharsets.UTF_8.name())

  private val id: I = KeyCodec[I]
    .decode(entityId)
    .getOrElse(throw new IllegalArgumentException(s"Failed to decode entity id from [$entityId]"))

  override val persistenceId: String = AkkaPersistenceRuntimeActor.combineEncodedId(entityName, entityId)

  private val recoveryStartTimestamp: Instant = Instant.now()

  override def preStart(): Unit = {
    log.info("[{}] Starting...", persistenceId)
  }

  protected var state: State = initialState(id)

  private var eventCount      = 0L
  private var snapshotPending = false

  private def recover(repr: PersistRepr): Unit =
    serDe.decodeWrapper(repr) match {
      case Failure(cause) =>
        onRecoveryFailure(cause, Some(repr))
      case Success(event) =>
        replayEvent(event)
        eventCount += 1
    }

  override def receiveRecover: Receive = {
    case repr: PersistRepr                           =>
      recover(repr)

    case Tagged(repr: PersistRepr, _)                =>
      recover(repr)

    case SnapshotOffer(_, snapshotRepr: PersistRepr) =>
      snapshotPolicy match {
        case Never                           => ()
        case e @ EachNumberOfEvents(_, _, _) =>
          e.decode(snapshotRepr) match {
            case Failure(cause)    =>
              onRecoveryFailure(cause, Some(snapshotRepr))
            case Success(snapshot) =>
              state = snapshot
          }
      }

    case RecoveryCompleted                           =>
      state = recoverCompleted(state)
      log.info(
        "[{}] Recovery to seqNr [{}] over [{}] events completed in [{} ms]",
        persistenceId,
        lastSequenceNr,
        eventCount,
        Duration.between(recoveryStartTimestamp, Instant.now()).toMillis
      )
      setIdleTimeout()

    case other                                       =>
      throw new IllegalStateException(s"Unexpected message during recovery [$other]")
  }

  override def receiveCommand: Receive = {
    case HandleCommand(command)           =>
      handleCommand(command.toByteArray)
    case ReceiveTimeout                   =>
      passivate()
    case AkkaPersistenceRuntimeActor.Stop =>
      context.stop(self)
    case ActionResult(opId, _, _, _)      =>
      log.warning(s"[$persistenceId] Received result of unknown command invocation [$opId]")
  }

  private def handleCommand(commandBytes: Array[Byte]): Unit =
    M.decoder.apply(commandBytes) match {
      case Success(pair)  =>
        log.debug("[{}] [{}] Received invocation [{}]", self.path, persistenceId, pair.first.toString)
        performInvocation(pair.first, pair.second)
      case Failure(cause) =>
        log.error(cause, s"Failed to decode invocation [${commandBytes.mkString("")}]")
        sender() ! Status.Failure(cause)
    }

  def performInvocation[A](invocation: Invocation[M, A], resultEncoder: Encoder[A]): Unit = {

    val opId = UUID.randomUUID()
//    val opsEnv = ZCadaver.Ops[I](id, lastSequenceNr, eventCount, entityId)
//    val effect = invocation
//      .run(actions)
//      .execute(env, opsEnv)
//
//    zio.Runtime.default.unsafeRunAsync(effect) {
//      case Exit.Success(value) => sender() ! value
//      case Exit.Failure(cause) => sender() ! Status.Failure(WrappedCause(cause))
//    }

//      .run(KeyedCtx(id, Ctx(lastSequenceNr, eventCount, entityId)), state, updateState)
//      .flatMap {
//        case (events, s, result) =>
//          F.delay(
//            log
//              .debug("[{}] Command [{}] produced reply [{}] and events [{}]", persistenceId, invocation, result, events)
//          ) map (_ => ActionResult(opId, events, s, resultEncoder.apply(result)))
//      }
//      .unsafeToFuture()
//      .pipeTo(self)(sender())
    context.become {
      case ActionResult(`opId`, events, s, resultBytes) =>
        handleCommandResult(events, s, CommandResult(ByteString.copyFrom(resultBytes)))
        unstashAll()
        context.become(receiveCommand)
      case Status.Failure(e)                            =>
        sender() ! Status.Failure(e)
        unstashAll()
        context.become(receiveCommand)
      case _                                            =>
        stash()
    }
  }

  private def handleCommandResult(events: Chain[Event], s: State, response: CommandResult): Unit = {
    // New addition, start the state from the result spot
    // Instead of recalculating state we use the result, so we can set it, in ActionT
    state = s
    val replyTo = sender()

    if (events.isEmpty) {
      replyTo ! response
    } else {
      val indexedEvs = events.toVector
      val envelopes  =
        indexedEvs.map(e => Tagged(serDe.encodeWrapper(e), tagger(id).map(_.value)))

      var unpersistedEventCount = events.size
      if (unpersistedEventCount == 1) {
        persist(envelopes.head) { _ =>
          replyTo ! response
          eventCount += 1
          markSnapshotAsPendingIfNeeded()
          snapshotIfPending()
        }
      } else {
        persistAll(envelopes) { _ =>
          unpersistedEventCount -= 1
          eventCount += 1
          markSnapshotAsPendingIfNeeded()
          if (unpersistedEventCount == 0) {
            replyTo ! response
            snapshotIfPending()
          }
        }
      }
    }
  }

  private def replayEvent(event: Event): Unit =
    state = updateState(state, event)

  private def markSnapshotAsPendingIfNeeded(): Unit =
    snapshotPolicy match {
      case EachNumberOfEvents(numberOfEvents, _, _) if eventCount % numberOfEvents == 0 =>
        snapshotPending = true
      case _                                                                            => ()
    }

  private def snapshotIfPending(): Unit =
    snapshotPolicy match {
      case e @ EachNumberOfEvents(numberOfEvents, keepN, _) if snapshotPending =>
        if (keepN > 0) {
          deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = lastSequenceNr - (keepN * numberOfEvents)))
        }
        saveSnapshot(e.encode(state))
        snapshotPending = false
      case _                                                                   => ()
    }

  private def passivate(): Unit = {
    log.debug("[{}] Passivating...", persistenceId)
    context.parent ! ShardRegion.Passivate(AkkaPersistenceRuntimeActor.Stop)
  }

  private def setIdleTimeout(): Unit = {
    log.debug("[{}] Setting idle timeout to [{}]", persistenceId, idleTimeout)
    context.setReceiveTimeout(idleTimeout)
  }
}
