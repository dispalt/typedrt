package zio.zproc

import java.util.concurrent.TimeUnit

import zio.clock.Clock
import zio.duration.Duration
import zio.test.Assertion.equalTo
import zio.test.environment.TestClock
import zio.test._
import zio.zproc.StatefulRuntime.SignalServerFn
import zio.{ZEnv, ZIO}

object ZProcSpec extends DefaultRunnableSpec {

  sealed trait Sig[-R, +E, +State, A]
  case class Test(foo: Int) extends Sig[Any, Nothing, St, Int]

  case class St(a: Int)

  def spec = suite("ZProc")(testM("no side effect") {
    val result: ZProc[Any with Clock, Nothing, St, Unit] = for {
      _  <- ZProc.pure[St, String]("a")
      b  <- ZProc.pure("b")
      b0 <- ZProc.lift(ZIO.succeed("b"))
      b0 <- ZProc.lift(ZIO.succeed(assert(b0)(equalTo(b))))
      c  <- ZProc.pure("c")
      c0 <- ZProc.lift(ZIO.succeed(assert(c)(equalTo("c"))))
      _  <- ZProc.lift(ZIO.succeed(println(b0)))
      _  <- ZProc.await[St](c => ZIO.succeed(c.a == 1))
      _  <- ZProc.lift(ZIO.succeed(println("Past it!")))
      _  <- ZProc.delay(Duration(1, TimeUnit.HOURS))
      _  <- ZProc.lift(ZIO.succeed(println("Past delay!")))
    } yield {
      ()
    }

    val d = StatefulRuntime.deploy[ZEnv, Nothing, St, String, Sig](
      "",
      St(0),
      result,
      new SignalServerFn[ZEnv, Nothing, St, Sig] {
        override def apply[A](fa: Sig[ZEnv, Nothing, St, A]): ZProc[ZEnv, Nothing, St, A] = fa match {
          case Test(foo) =>
            ZProc
              .lift(ZIO.succeed(println(s"Client Side Called ${foo}!")))
              .flatMap { _ =>
                ZProc.pure[St, Int](foo)
              }
              .flatMap { f =>
                ZProc
                  .mapState((s: St) => s.copy(f))
                  .map(_ => f)
              }

        }
      }
    )

    d.flatMap { signals =>
      signals(Test(200)) *>
        signals(Test(1)) *>
        TestClock.adjust(Duration(2, TimeUnit.HOURS)) *>
        signals.fiber.join.map { _ =>
          assertCompletes
        }
    }
  })

}
