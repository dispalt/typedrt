package com.goodcover.akkart.tests

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.testkit.TestKit
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest._
import akka.stream.scaladsl.Sink
import cats.effect.IO
import cats.implicits._
import com.dispalt.tagless.util.WireProtocol.{Decoder, Encoder}
import com.dispalt.taglessAkka.AkkaCodecFactory
import com.goodcover.akkart.{AkkaPersistenceRuntime, EitherKT, Entities}
import com.goodcover.encoding.KeyCodec
import com.goodcover.typedrt.CassandraJournalAdapter
import com.goodcover.typedrt.data.Tagging

import scala.concurrent.duration._

object AkkaPersistenceRuntimeSpec {

  def conf: Config = ConfigFactory
    .parseString(s"""
        akka {
          actor {
            provider = cluster
//            serializers {
//              kryo = "com.twitter.chill.akka.AkkaSerializer"
//            }
            allow-java-serialization = true
          }
          remote {
            netty.tcp {
              hostname = 127.0.0.1
              port = 0
              bind.hostname = "0.0.0.0"
              bind.port = 0
            }
          }
        }
        aecor.generic-akka-runtime.idle-timeout = 1s
     """)
    .withFallback(CassandraLifecycle.config)
    .withFallback(ConfigFactory.load())
}

class AkkaPersistenceRuntimeSpec
    extends TestKit(ActorSystem("AkkaPersistenceRuntimeSpec", AkkaPersistenceRuntimeSpec.conf))
    with funsuite.AnyFunSuiteLike
    with matchers.should.Matchers
    with ScalaFutures
    with CassandraLifecycle {

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    Cluster(system).join(Cluster(system).selfAddress)
  }

  override def systemName: String = system.name

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(60.seconds, 200.millis)

  // This is used to wait for draining
  private val timer = IO.timer(system.dispatcher)

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  // Setup separate tagging for each test. For instance in the journal test we only look at tag1
  val tag1: Tagging.Const[CounterId] = Tagging.const[CounterId](CounterEvent.tag)
  val tag2: Tagging.Const[CounterId] = Tagging.const[CounterId](CounterEvent.tag2)

  val runtime = AkkaPersistenceRuntime(system, CassandraJournalAdapter(system))

  // This allows the enrichment of the wire protocol, that gets summoned in the deploy call below.
  // This interface kind of sucks because with kryo anything encodes and decodes, but I am taking shortcuts
  // and this is where we are in the this shortcut, declaring bullshit implicits.
  implicit val exceptionEnc: Encoder[Throwable] = AkkaCodecFactory.encode[Throwable]
  implicit val exceptionDec: Decoder[Throwable] = AkkaCodecFactory.decode[Throwable]

  // So CounterAlg has two different implementations, one with rejections and the other without,
  // it's kind of hard to line the types up but just remember that everything is summoned so it's alright.
  test("Rejection should work") {
    // My least favorite thing about this is the `Entities.fromEitherKT(_)`, this basically takes the return type
    // which is typically F[K => M[F]], but since its rejectable, its much crazier Key => EitherKT[Counter, Throwable, IO],
    // which sucks to use so it makes it into something more Either-like. vs the HK EIther type.
    val deployIt =
      runtime.deploy("Counter2", CounterRejectableActions.behavior[IO], tag2).map(Entities.fromEitherKT(_))

    // Every command can fail here. Within the for comprehension, the res variable is Either[Throwable, Long]
    val program = for {
      counters <- deployIt
      third = counters(CounterId("3"))
      res <- third.decrement
    } yield res

    // This tests that it failed because scalatest is fucking crazy and lets you write hieroglyphics.
    program.unsafeRunSync() shouldBe Symbol("left")
  }

  test("Runtime should work") {
    // Alright the simpler version and will most likely be 99% of our services.  The straight counter.
    // That return type sure does look nice
    val deployCounters: IO[CounterId => Counter[IO]] = runtime.deploy("Counter", CounterActions.instance[IO], tag1)
    val program                                      = for {
      // Like Mvar's its a producer, so you start with it, and then you have your CounterId => Counter[IO]
      counters <- deployCounters
      // Now we are producing two individual runtimes for which to act on.
      first  = counters(CounterId("1"))
      second = counters(CounterId("2"))
      _                <- first.increment
      _                <- first.increment
      _                <- first.increment
      _                <- first.increment
      _                <- second.increment
      _2               <- second.value
      _                <- first.decrement
      // Here we are using the context to get the current seq number, but since we've programmed the seq number
      // to increment based on the number of events, we are good to go, it's supposed to reflect that.
      _3               <- first.incrementAndGetSeq
      _1               <- first.value
      // Here I am proving that the identity does in fact work
      _4               <- second.id
      afterPassivation <- timer.sleep(2.seconds) >> second.value
    } yield (_1, _2, _3, _4, afterPassivation)

    program.unsafeRunSync() shouldEqual ((4L, 1L, 6L, CounterId("2"), 1L))
  }

  test("Journal should work") {
    val journal = runtime.journal[CounterId, CounterEvent]
    val entries = journal.currentEventsByTag(CounterEvent.tag, None).runWith(Sink.seq).futureValue

    val map = entries.map(_.event).groupBy(_.key)
    map(CounterId("1")).size shouldBe 6
    map(CounterId("2")).size shouldBe 1
  }
}
