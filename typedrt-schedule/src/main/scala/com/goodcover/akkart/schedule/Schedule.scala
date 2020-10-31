package com.goodcover.akkart.schedule

import java.time.{Clock => _, _}
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import cats.effect.Effect
import cats.implicits._
import com.datastax.driver.core.utils.UUIDs
import com.goodcover.akkart.{AkkaPersistenceRuntimeSettings, _}
import com.goodcover.akkart.schedule.process.{DefaultScheduleEventJournal, PeriodicProcessRuntime, ScheduleProcess}
import com.goodcover.akkart.read.JournalEntry
import com.goodcover.fxn.Clock
import com.goodcover.processing.{DistributedProcessing, DistributedProcessingSettings}
import com.goodcover.typedrt.data.{Committable, ConsumerId, EventTag, KeyValueStore, TagConsumer, Tagging}
import com.goodcover.typedrt.CassandraJournalAdapter
import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.duration._

trait Scheduling[F[_]] {
  def addScheduleEntry(scheduleName: String, entryId: String, correlationId: String, dueDate: LocalDateTime): F[Unit]
  def removeScheduleEntry(scheduleName: String, entryId: String, dueDate: LocalDateTime): F[Unit]
}

trait Schedule[F[_]] extends Scheduling[F] {

  def committableScheduleEvents(
    scheduleName: String,
    consumerId: ConsumerId
  ): Source[Committable[F, JournalEntry[UUID, ScheduleBucketId, ScheduleEvent]], NotUsed]
}

object Schedule {

  final case class ScheduleSettings(
    bucketLength: FiniteDuration,
    refreshInterval: FiniteDuration,
    eventualConsistencyDelay: FiniteDuration,
    consumerId: ConsumerId,
    customSettings: Option[AkkaPersistenceRuntimeSettings] = None,
    distributedProcessingSettings: Option[DistributedProcessingSettings] = None)

  object ScheduleSettings {

    def default: ScheduleSettings =
      ScheduleSettings(1.day, 10.seconds, 40.seconds, ConsumerId("com.goodcover.akkart.schedule.ScheduleProcess"))
  }

  def start[F[_]: Effect](
    entityName: String,
    dayZero: LocalDate,
    clock: Clock[F],
    repository: ScheduleEntryRepository[F],
    offsetStore: KeyValueStore[F, TagConsumer, UUID],
    settings: ScheduleSettings = ScheduleSettings.default
  )(
    implicit system: ActorSystem,
    materializer: Materializer,
  ): F[Schedule[F]] = {

    val eventTag = EventTag(entityName)

    val runtime = AkkaPersistenceRuntime[UUID](system, CassandraJournalAdapter(system))

    def uuidToLocalDateTime(zoneId: ZoneId): KeyValueStore[F, TagConsumer, LocalDateTime] =
      offsetStore.imap[LocalDateTime](
        uuid => LocalDateTime.ofInstant(Instant.ofEpochMilli(UUIDs.unixTimestamp(uuid)), zoneId),
        value => UUIDs.startOf(value.atZone(zoneId).toInstant.toEpochMilli)
      )

    implicit val taskClock: Clock[F] = clock

    def deployBuckets =
      runtime
        .deploy(
          entityName,
          DefaultScheduleBucket.behavior(taskClock.zonedDateTime),
          Tagging.const[ScheduleBucketId](eventTag),
          customSettings = settings.customSettings
        )

    def startProcess(buckets: ScheduleBucketId => ScheduleBucket[F]): F[DistributedProcessing.ProcessKillSwitch[F]] =
      clock.zone.flatMap { zone =>
        val journal =
          DefaultScheduleEventJournal[F](
            settings.consumerId,
            8,
            runtime.journal[ScheduleBucketId, ScheduleEvent].committable(offsetStore),
            eventTag
          )

        val process = ScheduleProcess[F](
          journal,
          dayZero,
          settings.consumerId,
          uuidToLocalDateTime(zone),
          settings.eventualConsistencyDelay,
          repository,
          buckets,
          clock.localDateTime,
          8
        )
        PeriodicProcessRuntime(entityName, settings.refreshInterval, process)
          .run(system, settings.distributedProcessingSettings)
      }

    def createSchedule(buckets: ScheduleBucketId => ScheduleBucket[F]): Schedule[F] =
      new DefaultSchedule(
        clock,
        buckets,
        settings.bucketLength,
        runtime.journal[ScheduleBucketId, ScheduleEvent].committable(offsetStore),
        eventTag
      )

    for {
      buckets <- deployBuckets
      _       <- startProcess(buckets)
    } yield createSchedule(buckets)
  }

}
