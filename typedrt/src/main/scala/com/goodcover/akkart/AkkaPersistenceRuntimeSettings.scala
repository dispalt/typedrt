package com.goodcover.akkart

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.cluster.sharding.ClusterShardingSettings

import scala.concurrent.duration.{Duration, FiniteDuration}

final case class AkkaPersistenceRuntimeSettings(
  numberOfShards: Int,
  idleTimeout: FiniteDuration,
  askTimeout: FiniteDuration,
  clusterShardingSettings: ClusterShardingSettings)

object AkkaPersistenceRuntimeSettings {

  /**
    * Reads config from `goodcover.akka-runtime`, see reference.conf for details
    * @param system Actor system to get config from
    * @return default settings
    */
  def apply(system: ActorSystem): AkkaPersistenceRuntimeSettings = {
    val config                                          = system.settings.config.getConfig("goodcover.akka-runtime")
    def getMillisDuration(path: String): FiniteDuration =
      Duration(config.getDuration(path, TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)

    AkkaPersistenceRuntimeSettings(
      config.getInt("number-of-shards"),
      getMillisDuration("idle-timeout"),
      getMillisDuration("ask-timeout"),
      ClusterShardingSettings(system)
    )
  }
}
