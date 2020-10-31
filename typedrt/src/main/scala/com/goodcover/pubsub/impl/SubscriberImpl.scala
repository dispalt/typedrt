package com.goodcover.pubsub.impl

import cats.implicits._
import cats.effect.implicits._
import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.pubsub.DistributedPubSubMediator.{Subscribe, SubscribeAck}
import cats.effect.{Concurrent, ConcurrentEffect, IO}
import cats.effect.concurrent.Deferred
import com.goodcover.pubsub.{MessageEnvelope, Subscriber}
import com.goodcover.pubsub.impl.SubscriberImpl.SubscriberActor
import monix.catnap.ConcurrentQueue

private[pubsub] trait SubscriberImpl[F[_], A] extends Subscriber[F, A] {
  val getActorSystem: ActorSystem
  val getMediator: ActorRef

  override implicit val F: ConcurrentEffect[F]

  override def unsafeListenWith(topic: String, queue: ConcurrentQueue[F, A], group: Option[String] = None): F[Unit] =
    for {
      subscribed <- Deferred[F, Unit]
      _          <-
        F.delay(getActorSystem.actorOf(Props(new SubscriberActor[F, A](getMediator, topic, group, queue, subscribed))))
      _          <- subscribed.get
    } yield ()
}

object SubscriberImpl {

  private[impl] class SubscriberActor[F[_], A](
    mediator: ActorRef,
    topic: String,
    group: Option[String],
    queue: ConcurrentQueue[F, A],
    subscribed: Deferred[F, Unit]
  )(
    implicit F: ConcurrentEffect[F])
      extends Actor {

    mediator ! Subscribe(topic, group, self)

    def receive: PartialFunction[Any, Unit] = {
      case SubscribeAck(_)      =>
        subscribed.complete(()).toIO.unsafeRunSync()

      case MessageEnvelope(msg) =>
        queue
          .offer(msg.asInstanceOf[A])
          .toIO
          .runAsync {
            case Right(_)    => IO.pure(())
            case Left(cause) => IO(self ! PoisonPill) // stop listening if the queue was shut down
          }
          .unsafeRunSync()
    }
  }
}
