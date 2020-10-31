package com.goodcover.pubsub.impl

import akka.actor.ActorRef
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import cats.effect.Sync
import com.goodcover.pubsub.{MessageEnvelope, Publisher}

private[pubsub] trait PublisherImpl[F[_], A] extends Publisher[F, A] {
  val getMediator: ActorRef

  val F: Sync[F]

  override def unsafePublish(topic: String, data: A, sendOneMessageToEachGroup: Boolean = false): F[Unit] =
    F.delay(getMediator ! Publish(topic, MessageEnvelope(data), sendOneMessageToEachGroup))
}
