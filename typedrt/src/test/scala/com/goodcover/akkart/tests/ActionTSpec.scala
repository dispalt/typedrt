package com.goodcover.akkart.tests

import cats.Id
import cats.data.{Chain, NonEmptyChain}
import cats.instances.string._
import cats.syntax.applicative._
import cats.syntax.flatMap._
import com.goodcover.akkart.{ActionT, Ctx, KeyedCtx}
import org.scalatest._

class ActionTSpec extends flatspec.AnyFlatSpec {
  def append(s: String): ActionT[Id, String, String, String, Unit] = ActionT.append(NonEmptyChain.one(s))
  def read: ActionT[Id, String, String, String, String]            = ActionT.read

  def run[A](action: ActionT[Id, String, String, String, A]): (Chain[String], String, A) =
    action.run(KeyedCtx("", Ctx(1L, 1L, "")), "", (l, r) => (l ++ r))

  "ActionT" should "have read associativity" in {
    val n1 @ (es, _, out) = run(append("a") >> (append("b") >> read))
    val n2                = run(append("a") >> append("b") >> read)
    assert(es === Chain("a", "b"))
    assert(out === "ab")
    assert(n1 === n2)
  }

}
