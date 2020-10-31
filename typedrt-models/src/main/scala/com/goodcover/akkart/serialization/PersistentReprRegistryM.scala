package com.goodcover.akkart.serialization

import scalapb._

import scala.reflect.macros.blackbox

object PersistentReprRegistryM {

  def applyM[M <: GeneratedMessage](t: GeneratedMessageCompanion[M]): PersistCodec[M] =
    macro PersistentReprRegistryMImpl.imp[M]

}

object PersistentReprRegistryMImpl {

  def imp[M: c.WeakTypeTag](c: blackbox.Context)(t: c.Expr[M]): c.Expr[PersistCodec[M]] = {
    import c.universe._
    val f = c.internal.enclosingOwner
    if (!f.isTerm)
      c.abort(c.enclosingPosition, s"Expected a term, got a $f")

    val pterm   = f.asTerm
    val decName = pterm.name.decodedName.toString
    if (!decName.startsWith("_"))
      c.abort(c.enclosingPosition, "To use this you must name the implicit val _$number (substituting in the number)")

    val num   =
      try decName.substring(1).trim.toLong
      catch {
        case e: NumberFormatException =>
          c.abort(
            c.enclosingPosition,
            "This function needs to be in the " +
              s"strict form of implicit val _$$long to work.  $decName, not recognized as adhering to that, Exception: $e."
          )
      }
    val quote = q"""apply($num, $t)"""
    c.Expr[PersistCodec[M]](quote)
  }
}
