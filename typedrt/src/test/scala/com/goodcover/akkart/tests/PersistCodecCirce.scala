package com.goodcover.akkart.tests

import java.nio.ByteBuffer

import akka.persistence.PersistentRepr
import com.goodcover.akkart.msg
import com.goodcover.akkart.msg.PersistRepr
import com.goodcover.akkart.serialization.PersistCodec
import com.google.protobuf.ByteString
import io.circe.{jawn, Decoder, Encoder, Json}

import scala.util.Try

object PersistCodecCirce {

  def circePersistCodec[A](implicit encoder: Encoder[A], dec: Decoder[A]): PersistCodec[A] = new PersistCodec[A] {

    override def encodeWrapper(m: A): PersistRepr =
      PersistRepr("", com.google.protobuf.ByteString.copyFrom(encoder(m).noSpaces.getBytes()))

    override def decodeWrapper(m: PersistRepr): Try[A] = {
      jawn
        .parseByteBuffer(ByteBuffer.wrap(m.payload.toByteArray))
        .flatMap { f =>
          dec.decodeJson(f)
        }
        .toTry
    }
  }
}
