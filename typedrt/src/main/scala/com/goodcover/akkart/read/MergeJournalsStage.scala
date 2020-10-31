package com.goodcover.akkart.read

import java.time.{Duration, Instant}
import java.util.UUID

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler, TimerGraphStageLogic}
import akka.stream.{Attributes, Inlet, Outlet, UniformFanInShape}
import com.datastax.driver.core.utils.UUIDs

import scala.collection.immutable
import scala.math.Ordering.Implicits._

// TODO: Get rid of offset casts

private class MergeJournalsStage[O, K, E](
  val nInputs: Int,
  live: Boolean,
  maybeEventualConsistencyDuration: Option[Duration])
    extends GraphStage[UniformFanInShape[JournalEntry[O, K, E], JournalEntryOrHeartBeat[O, K, E]]] {

  private[this] val eventualConsistencyDuration = maybeEventualConsistencyDuration.getOrElse(Duration.ofSeconds(15))
  private[this] val updateDuration              = Duration.ofSeconds(15)

  private[this] val inputStates: Array[InputState] = Array.fill(nInputs)(InputState.Idle)

  private[this] var lastPushedOffset: Option[O] = None

  val inputs: immutable.IndexedSeq[Inlet[JournalEntry[O, K, E]]] =
    Vector.tabulate(nInputs)(i => Inlet[JournalEntry[O, K, E]]("MergeEventsStage.in" + i))
  val output: Outlet[JournalEntryOrHeartBeat[O, K, E]]           = Outlet("MergeEventsStage.out")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) {
      for ((input, i) <- inputs.zipWithIndex) {
        setHandler(
          input,
          new InHandler {
            override def onPush(): Unit = {
              inputStates(i) match {
                case InputState.RequestSent(_) => {
                  val nowInstant = Instant.now()

                  val event = grab(input)
                  require(
                    !lastPushedOffset.exists(o => uuidTimestamp(o) >= uuidTimestamp(event.offset)),
                    s"Pulled an event with an offset smaller than a previously pushed event. lastPushedOffset=[${lastPushedOffset}]"
                  )
                  require(
                    !live || instant(event.offset) < nowInstant.plus(eventualConsistencyDuration),
                    "Pulled an event timestamped too far into the future."
                  )

                  inputStates(i) = InputState.PendingEvent(event)
                  pushAndPull(nowInstant)
                }
              }
            }
            override def onUpstreamFinish(): Unit = {
              val nowInstant = Instant.now()

              if (inputStates(i).isInstanceOf[InputState.RequestSent]) {
                inputStates(i) = InputState.Idle
                pushAndPull(nowInstant)
              }
            }
          }
        )
      }
      setHandler(
        output,
        new OutHandler {

          override def onPull(): Unit = {
            val nowInstant = Instant.now()

            pushAndPull(nowInstant)
          }
        }
      )

      override def preStart(): Unit = {
        scheduleAtFixedRate(UpdateTimer, updateDuration, updateDuration)
      }

      override protected def onTimer(timerKey: Any): Unit = {
        timerKey match {
          case UpdateTimer => pushAndPull(Instant.now(), timerTriggered = true)
        }
      }

      private[this] def pushAndPull(nowInstant: Instant, timerTriggered: Boolean = false): Unit = {
        val consistentInstant = nowInstant.minus(eventualConsistencyDuration)

        for ((inputState, i) <- inputStates.zipWithIndex) {
          if (inputState == InputState.Idle) {
            pull(i, nowInstant)
          }
        }

        if (isAvailable(output)) {
          val waitingForResponses = inputStates.exists {
            case InputState.RequestSent(requestInstant) => !live || requestInstant >= consistentInstant
            case _                                      => false
          }

          if (!waitingForResponses) {
            val pushableEvents = inputStates.zipWithIndex.collect {
              case (InputState.PendingEvent(event), i) if !live || instant(event.offset) <= consistentInstant =>
                (event, i)
            }
            if (pushableEvents.nonEmpty) {
              val (oldestEvent, i) = pushableEvents.minBy { case (event, _) => uuidTimestamp(event.offset) }
              push(output, oldestEvent)
              lastPushedOffset = Some(oldestEvent.offset)
              pull(i, nowInstant)
            } else if (inputStates.forall(s => s == InputState.Idle)) {
              completeStage()
            } else {
              if (live && timerTriggered) {
                val o = offset(consistentInstant)
                push(output, HeartBeat[O, K, E](o))
                lastPushedOffset = Some(o)
              }
            }
          }
        }
      }

      private[this] def pull(inputIndex: Int, nowInstant: Instant): Unit = {
        val input = inputs(inputIndex)
        if (!isClosed(input)) {
          pull(input)
          inputStates(inputIndex) = InputState.RequestSent(nowInstant)
        } else {
          inputStates(inputIndex) = InputState.Idle
        }
      }
    }

  override def shape: UniformFanInShape[JournalEntry[O, K, E], JournalEntryOrHeartBeat[O, K, E]] =
    UniformFanInShape(output, inputs: _*)

  private[this] def uuidTimestamp(offset: O): Long = offset.asInstanceOf[UUID].timestamp()

  private[this] def instant(offset: O): Instant = Instant.ofEpochMilli(UUIDs.unixTimestamp(offset.asInstanceOf[UUID]))

  private[this] def offset(instant: Instant): O = UUIDs.startOf(instant.toEpochMilli).asInstanceOf[O]

  private[this] case object UpdateTimer

  private[this] sealed abstract class InputState

  private[this] object InputState {
    case object Idle                                      extends InputState
    case class PendingEvent(event: JournalEntry[O, K, E]) extends InputState
    case class RequestSent(instant: Instant)              extends InputState
  }
}
