package com.goodcover.pubsub

import cats.effect.Sync
import shared.models.TypedField

class MockPubSub[F[_], A](implicit F: Sync[F]) extends Publisher[F, A] {

  def unsafePublish(topic: String, data: A, sendOneMessageToEachGroup: Boolean = false): F[Unit] = {
    F.delay(println(s"$topic - $data"))
  }
}
