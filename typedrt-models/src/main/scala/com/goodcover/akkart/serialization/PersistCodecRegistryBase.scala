package com.goodcover.akkart.serialization

import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator

import com.goodcover.akkart.msg.PersistRepr
import PersistCodec.DecodeFailure
import scalapb.{GeneratedMessage, GeneratedMessageCompanion, Message}

import scala.annotation.implicitNotFound
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}
import scala.util.control.NoStackTrace

@implicitNotFound("""
You are probably missing an import for your declared implicit.
Check if if PersistSerde[${M}] is imported, it usually lives
in a common object inherited from PersistCodecRegistryBase.

The object to import should be `com.foo.<Blah>PersistCodecRegistry._`

For instance: `com.goodcover.serialization.a1.A1PersistCodecRegistry._`

""")
trait PersistCodec[M] { self =>

  def encode(m: M): Array[Byte] =
    encodeWrapper(m).toByteArray

  def encodeWrapper(m: M): PersistRepr

  def decode(ab: Array[Byte]): Try[M] =
    PersistRepr.validate(ab).flatMap(decodeWrapper)

  def decodeWrapper(m: PersistRepr): Try[M]

  def widen[B >: M]: PersistCodec[B] = this.asInstanceOf[PersistCodec[B]]

  def imap[B](decFn: M => B)(encFun: B => M): PersistCodec[B] = new PersistCodec[B] {
    override def encodeWrapper(m: B): PersistRepr = self.encodeWrapper(encFun(m))

    override def decodeWrapper(m: PersistRepr): Try[B] = self.decodeWrapper(m).map(decFn)
  }
}

object PersistCodec {

  /**
    * Specifically failure for decoding a valid wrapper with no corresponding protobuf (id -> pbuf decoder)
    */
  case class DecodeFailure(message: String) extends NoStackTrace {
    override def getMessage: String = message
  }

  def apply[M](implicit persistSerde: PersistCodec[M]): PersistCodec[M] = persistSerde

  private[this] var registeredPrefixes = Set.empty[String]

  def contains(s: String): Boolean = registeredPrefixes.contains(s)

  def update(s: String, clazz: Class[_]): String = {
    println(s"Registered prefix $s for registry ${clazz.getName}")
    registeredPrefixes += s; s
  }
}

/**
  * In general one should never make any of the semiauto derived things lazy, since they register with a central
  * decoder registry.
  */
abstract class PersistCodecRegistryBase {

  def registerPrefix(string: String): String = PersistCodec.synchronized {
    if (PersistCodec.contains(string)) {
      sys.error(s"Already registered $string prefix")
    } else PersistCodec.update(string, getClass)
  }

  /* TODO: Come up with better locking mechanism here.*/
  private[serialization] val idToCompanion = new AtomicReference(Map.empty[String, GeneratedMessageCompanion[_]])

  /* Convenience */
  def apply[M <: GeneratedMessage](l: String, comp: GeneratedMessageCompanion[M]): PersistCodec[M] =
    this.synchronized {
      val prev = idToCompanion.getAndUpdate(new UnaryOperator[Map[String, GeneratedMessageCompanion[_]]] {
        override def apply(t: Map[String, GeneratedMessageCompanion[_]]) =
          t.updated(l, comp.asInstanceOf[GeneratedMessageCompanion[_]])
      })

      if (prev.contains(l))
        sys.error(s"# $l Already exists $comp")

      new PersistCodec[M] {

        def encodeWrapper(m: M): PersistRepr = {
          PersistRepr.of(l, m.toByteString)
        }

        override def decodeWrapper(m: PersistRepr): Try[M] = {
          comp.validate(m.payload.toByteArray)
        }
      }
    }

  /** You can only forgo on decoding, but this is how you do it. */
  def decodeRaw(ab: Array[Byte]): Try[GeneratedMessage] = {
    PersistRepr.validate(ab) flatMap { wrap =>
      decodeWrap(wrap)
    }
  }

  def decodeWrap(wrap: PersistRepr): Try[GeneratedMessage] = {

    idToCompanion.get().get(wrap.id) match {
      case Some(value) => value.validate(wrap.payload.toByteArray).map(_.asInstanceOf[GeneratedMessage])
      case None        => Failure(DecodeFailure(s"Could not find corresponding protobuf to id ${wrap.id}"))
    }
  }

  def encode[M](message: M)(implicit serde: PersistCodec[M]): PersistRepr = {
    serde.encodeWrapper(message)
  }

  def instance[A](enc: A => PersistRepr): PersistCodec[A] = new PersistCodec[A] {
    override def encode(m: A): Array[Byte]             = enc(m).toByteArray
    override def encodeWrapper(m: A): PersistRepr      = enc(m)
    override def decode(ab: Array[Byte]): Try[A]       = decodeRaw(ab).map(_.asInstanceOf[A])
    override def decodeWrapper(m: PersistRepr): Try[A] = decodeWrap(m).map(_.asInstanceOf[A])
  }

  /**
    * TODO: This name absolutely blows.
    */
  def instanceHierarchical[A](enc: A => PersistRepr, dec: GeneratedMessage => A): PersistCodec[A] =
    new PersistCodec[A] {
      override def encode(m: A): Array[Byte]             = enc(m).toByteArray
      override def encodeWrapper(m: A): PersistRepr      = enc(m)
      override def decode(ab: Array[Byte]): Try[A]       = decodeRaw(ab).map(dec)
      override def decodeWrapper(m: PersistRepr): Try[A] = decodeWrap(m).map(dec)
    }
}
