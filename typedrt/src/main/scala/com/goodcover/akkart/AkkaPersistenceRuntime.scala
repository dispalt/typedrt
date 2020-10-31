package com.goodcover.akkart

import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterSharding, ShardRegion}
import akka.persistence.Recovery
import akka.pattern.ask
import akka.util.Timeout
import cats.effect.Effect
import cats.tagless.FunctorK
import cats.tagless.syntax.functorK._
import cats.~>
import cats.implicits._
import com.dispalt.tagless.util.WireProtocol
import com.dispalt.tagless.util.WireProtocol.Encoded
import com.goodcover.akkart.serialization.PersistCodec
import com.goodcover.akkart.AkkaPersistenceRuntime.EntityCommand
import com.goodcover.akkart.AkkaPersistenceRuntimeActor.CommandResult
import com.goodcover.akkart.read.AkkaPersistenceJournalQuery.AkkaPersistenceJournalSettings
import com.goodcover.fxn._
import com.goodcover.akkart.read.{AkkaPersistenceJournalQuery, JournalQuery, JournalQueryScoped, JournalQueryUnscoped}
import com.goodcover.encoding.KeyCodec
import com.goodcover.typedrt.JournalAdapter
import com.goodcover.typedrt.data.Tagging
import com.goodcover.typedrt.serialization.MarkerMessage
import com.google.protobuf.ByteString

class AkkaPersistenceRuntime[O](system: ActorSystem, val adapter: JournalAdapter[O]) {

  def deploy[M[_[_]]: FunctorK, F[_], State, Event: PersistCodec, Key: KeyCodec](
    typeName: String,
    behavior: BehaviorM[M, F, Key, State, Event],
    tagging: Tagging[Key],
    snapshotPolicy: SnapshotPolicy[State] = SnapshotPolicy.never,
    customSettings: Option[AkkaPersistenceRuntimeSettings] = None,
    recoveryPolicy: Recovery = Recovery.create(),
  )(
    implicit M: WireProtocol[M],
    F: Effect[F],
  ): F[Key => M[F]] =
    F.delay {
      val settings = customSettings.getOrElse(AkkaPersistenceRuntimeSettings(system))
      val props    =
        AkkaPersistenceRuntimeActor.props(
          typeName,
          behavior.actions,
          behavior.create,
          behavior.update,
          snapshotPolicy,
          tagging,
          settings.idleTimeout,
          recoveryPolicy,
          adapter.writeJournalId,
          snapshotPolicy.pluginId,
          behavior.recoveryComplete,
        )

      val extractEntityId: ShardRegion.ExtractEntityId = {
        case EntityCommand(entityId, bytes) =>
          (entityId, AkkaPersistenceRuntimeActor.HandleCommand(bytes))
      }

      val numberOfShards = settings.numberOfShards

      val extractShardId: ShardRegion.ExtractShardId = {
        case EntityCommand(entityId, _) =>
          (scala.math.abs(entityId.hashCode) % numberOfShards).toString
        case other                      => throw new IllegalArgumentException(s"Unexpected message [$other]")
      }

      val shardRegion = ClusterSharding(system).start(
        typeName        = typeName,
        entityProps     = props,
        settings        = settings.clusterShardingSettings,
        extractEntityId = extractEntityId,
        extractShardId  = extractShardId
      )

      val keyEncoder                   = KeyCodec[Key]
      implicit val askTimeout: Timeout = Timeout(settings.askTimeout)

      val result: Key => M[F] = { key: Key =>
        M.encoder.mapK(new (Encoded ~> F) {

          override def apply[A](fa: Encoded[A]): F[A] = F.suspend {
            val (bytes, decoder) = fa
            F.fromFuture {
              shardRegion ? EntityCommand(keyEncoder(key), ByteString.copyFrom(bytes))
            }.flatMap {
              case CommandResult(resultBytes) =>
                F.fromTry(decoder.apply(resultBytes.toByteArray))
              case other                      =>
                F.raiseError(new IllegalArgumentException(s"Unexpected response [$other] from shard region"))
            }
          }
        })
      }
      result
    }

  def journal[K: KeyCodec, E: PersistCodec](typeName: String): JournalQueryScoped[O, K, E] =
    AkkaPersistenceJournalQuery[O, K, E](typeName, system, adapter, AkkaPersistenceJournalSettings())

  def journal[K: KeyCodec, E: PersistCodec]: JournalQueryUnscoped[O, K, E] =
    AkkaPersistenceJournalQuery[O, K, E](system, adapter, AkkaPersistenceJournalSettings())

  def journal[K: KeyCodec, E: PersistCodec](
    typeName: String,
    settings: AkkaPersistenceJournalSettings
  ): JournalQueryScoped[O, K, E] =
    AkkaPersistenceJournalQuery[O, K, E](typeName, system, adapter, settings)

  def journal[K: KeyCodec, E: PersistCodec](settings: AkkaPersistenceJournalSettings): JournalQueryUnscoped[O, K, E] =
    AkkaPersistenceJournalQuery[O, K, E](system, adapter, settings)

}

object AkkaPersistenceRuntime {
  final private[goodcover] case class EntityCommand(entityKey: String, commandBytes: ByteString) extends MarkerMessage

  def apply[O](system: ActorSystem, journalAdapter: JournalAdapter[O]): AkkaPersistenceRuntime[O] =
    new AkkaPersistenceRuntime(system, journalAdapter)
}
