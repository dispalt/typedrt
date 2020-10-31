package com.goodcover.akkart.serialization

import akka.actor.ExtendedActorSystem
import akka.serialization.{BaseSerializer, SerializerWithStringManifest}
import com.goodcover.akkart.msg.PersistRepr
import com.google.protobuf.ByteString

class PersistReprSerializer(val system: ExtendedActorSystem) extends SerializerWithStringManifest with BaseSerializer {

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case pr: PersistRepr => pr.payload.toByteArray
    case x               => throw new IllegalArgumentException(s"Serialization of [$x] is not supported")
  }

  override def manifest(o: AnyRef): String = o match {
    case pr: PersistRepr => pr.id
    case x               => throw new IllegalArgumentException(s"Serialization of [$x] is not supported")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    PersistRepr(manifest, ByteString.copyFrom(bytes))
}
