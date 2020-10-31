package com.goodcover.encoding

import scala.reflect.macros.blackbox

trait KeyCodec[A] { self =>

  def apply(a: A): String

  /**
    * Attempt to convert a String to a value.
    */
  def decode(key: String): Option[A]

  /**
    * Construct an instance for type `B` from an instance for type `A`.
    */
  final def imap[B](f: B => A)(f2: A => B): KeyCodec[B] = new KeyCodec[B] {
    final def apply(key: B): String  = self(f(key))
    override def decode(key: String) = self.decode(key).map(f2)
  }
}

object KeyCodec extends KeyCodecLowPriority {
  def apply[A](implicit A: KeyCodec[A]): KeyCodec[A] = A

  def instance[A](f: A => String, f2: String => Option[A]): KeyCodec[A] = new KeyCodec[A] {
    final def apply(key: A): String          = f(key)
    final def decode(key: String): Option[A] = f2(key)
  }

  implicit val decodeKeyString: KeyCodec[String] = new KeyCodec[String] {
    final def apply(key: String): String = key

    override def decode(key: String): Option[String] = Some(key)
  }

}

trait KeyCodecLowPriority {

  implicit def anyVal[A <: AnyVal]: KeyCodec[A] = macro KeyCodecLowPriority.impl[A]
}

object KeyCodecLowPriority {

  def impl[A](c: blackbox.Context)(implicit t: c.WeakTypeTag[A]): c.Expr[KeyCodec[A]] = {
    import c.universe._

    def fail(enc: String, t: Type) =
      c.abort(
        c.enclosingPosition,
        s"Can't find $enc for type '$t'. Note that ${enc}s are invariant. For example, use `lift(Option(1))` instead of `lift(Some(1))` since the available encoder is for `Option`, not `Some`. As an alternative for types that don't provide a method like `Option.apply`, you can use type widening: `lift(MyEnum.SomeValue: MyEnum.Value)`"
      )

    def withAnyValParam[R](tpe: Type)(f: Symbol => R): Option[R] =
      tpe.baseType(c.symbolOf[AnyVal]) match {
        case NoType => None
        case _      =>
          primaryConstructor(tpe).map(_.paramLists.flatten).collect {
            case param :: Nil => f(param)
          }
      }

    def primaryConstructor(t: Type) =
      t.members.collectFirst {
        case m: MethodSymbol if m.isPrimaryConstructor => m.typeSignature.asSeenFrom(t, t.typeSymbol)
      }

    c.Expr[KeyCodec[A]](withAnyValParam(t.tpe) { param =>
      q"""
        implicitly[_root_.com.goodcover.encoding.KeyCodec[${param.typeSignature}]].imap(
          (v: ${t.tpe}) => v.${param.name.toTermName})(
          new ${t.tpe}(_)
        )
      """
    }.getOrElse(fail("Decoder", t.tpe)))
  }
}
