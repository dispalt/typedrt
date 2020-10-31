package com.goodcover.typedrt.serialization

import akka.actor.ExtendedActorSystem
import akka.serialization.{BaseSerializer, SerializerWithStringManifest}
import com.goodcover.akkart.AkkaPersistenceRuntime.EntityCommand
import com.goodcover.processing.serialization.msgs
import com.goodcover.akkart.msg
import com.goodcover.akkart.AkkaPersistenceRuntimeActor.{CommandResult, HandleCommand}
import com.goodcover.processing.DistributedProcessingSupervisor.KeepRunning
import com.goodcover.processing.serialization.msgs.KeepRunningPB

class MessageSerializer(val system: ExtendedActorSystem) extends SerializerWithStringManifest with BaseSerializer {
  val KeepRunningManifest   = "A"
  val EntityCommandManifest = "B"
  val HandleCommandManifest = "C"
  val CommandResultManifest = "D"

  override def manifest(o: AnyRef): String = o match {
    case _: KeepRunning   => KeepRunningManifest
    case _: EntityCommand => EntityCommandManifest
    case _: HandleCommand => HandleCommandManifest
    case _: CommandResult => CommandResultManifest
    case x                => throw new IllegalArgumentException(s"Serialization of [$x] is not supported")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case kr: KeepRunning              => KeepRunningPB(kr.workerId).toByteArray
    case EntityCommand(entityKey, cb) => msg.EntityCommand(entityKey, cb).toByteArray
    case HandleCommand(cb)            => msg.HandleCommand(cb).toByteArray
    case CommandResult(cb)            => msg.CommandResult(cb).toByteArray
    case x                            => throw new IllegalArgumentException(s"Serialization of [$x] is not supported")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    manifest match {
      case KeepRunningManifest   =>
        val kr = msgs.KeepRunningPB.parseFrom(bytes)
        KeepRunning(kr.workerId)

      case EntityCommandManifest =>
        val ec = msg.EntityCommand.parseFrom(bytes)
        EntityCommand(ec.entityId, ec.commandBytes)

      case HandleCommandManifest =>
        val hcm = msg.HandleCommand.parseFrom(bytes)
        HandleCommand(hcm.commandBytes)

      case CommandResultManifest =>
        val cr = msg.CommandResult.parseFrom(bytes)
        CommandResult(cr.resultBytes)

      case other                 => throw new IllegalArgumentException(s"Unknown manifest [$other]")
    }
}
