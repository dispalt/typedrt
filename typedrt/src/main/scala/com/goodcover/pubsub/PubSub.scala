package com.goodcover.pubsub

import cats.implicits._
import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.pubsub.DistributedPubSub
import cats.effect.{Async, Concurrent, ConcurrentEffect, ContextShift, Sync, Timer}
import com.goodcover.pubsub.impl.{PublisherImpl, SubscriberImpl}
import monix.catnap.ConcurrentQueue
import shared.models.TypedField

/**
  *  A `Publisher[A]` is able to send messages of type `A` through Akka PubSub.
  */
trait Publisher[F[_], A] {
  def unsafePublish(topic: String, data: A, sendOneMessageToEachGroup: Boolean = false): F[Unit]

  def publish(topic: TypedField[A], data: A, sendOneMessageToEachGroup: Boolean = false): F[Unit] =
    unsafePublish(topic.value, data, sendOneMessageToEachGroup)
}

/**
  *  A `Subscriber[A]` is able to receive messages of type `A` through Akka PubSub.
  */
trait Subscriber[F[_], A] {

  implicit val T: Timer[F]
  implicit val F: Concurrent[F]
  implicit val cs: ContextShift[F]

  def listen(topic: TypedField[A], group: Option[String] = None): F[ConcurrentQueue[F, A]] =
    unsafeListen(topic.value, group)

  def unsafeListen(topic: String, group: Option[String] = None): F[ConcurrentQueue[F, A]] =
    ConcurrentQueue[F].unbounded[A](None).flatTap(cq => unsafeListenWith(topic, cq, group))

  def listenWith(topic: TypedField[A], queue: ConcurrentQueue[F, A], group: Option[String] = None): F[Unit] =
    unsafeListenWith(topic.value, queue, group)

  def unsafeListenWith(topic: String, queue: ConcurrentQueue[F, A], group: Option[String] = None): F[Unit]
}

/**
  *  A `PubSub[A]` is able to both send and receive messages of type `A` through Akka PubSub.
  */
trait PubSub[F[_], A] extends Publisher[F, A] with Subscriber[F, A]

object PubSub {

  private def getMediator[F[_]: Sync](actorSystem: ActorSystem): F[ActorRef] =
    Sync[F].delay(DistributedPubSub(actorSystem).mediator)

  /**
    *  Creates a new `Publisher[A]`.
    */
  def createPublisher[F[_]: Sync, A](implicit actorSystem: ActorSystem): F[Publisher[F, A]] =
    for {
      mediator <- getMediator[F](actorSystem)
    } yield new Publisher[F, A] with PublisherImpl[F, A] {

      override val F                     = Sync[F]
      override val getMediator: ActorRef = mediator
    }

  /**
    *  Creates a new `Subscriber[A]`.
    */
  def createSubscriber[F[_], A](
    implicit actorSystem: ActorSystem,
    T0: Timer[F],
    F0: ConcurrentEffect[F],
    cs0: ContextShift[F]
  ): F[Subscriber[F, A]] =
    for {
      mediator <- getMediator[F](actorSystem)
    } yield new Subscriber[F, A] with SubscriberImpl[F, A] {

      override implicit val T: Timer[F]            = T0
      override implicit val F: ConcurrentEffect[F] = F0
      override implicit val cs: ContextShift[F]    = cs0

      override val getActorSystem: ActorSystem = actorSystem
      override val getMediator: ActorRef       = mediator
    }

  /**
    *  Creates a new `PubSub[A]`.
    */
  def createPubSub[F[_], A](
    implicit actorSystem: ActorSystem,
    T0: Timer[F],
    F0: ConcurrentEffect[F],
    cs0: ContextShift[F]
  ): F[PubSub[F, A]] =
    for {
      mediator <- getMediator[F](actorSystem)
    } yield new PubSub[F, A] with PublisherImpl[F, A] with SubscriberImpl[F, A] {

      override implicit val T: Timer[F]            = T0
      override implicit val F: ConcurrentEffect[F] = F0
      override implicit val cs: ContextShift[F]    = cs0

      override val getActorSystem: ActorSystem = actorSystem
      override val getMediator: ActorRef       = mediator
    }

}
