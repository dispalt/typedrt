package com.goodcover.akkart.schedule

import com.goodcover.encoding.KeyCodec
import com.goodcover.typedrt.data.Composer

final case class ScheduleBucketId(scheduleName: String, scheduleBucket: String) {
  def encode: String = ScheduleBucketId.encoder(scheduleName, scheduleBucket)
}

object ScheduleBucketId {
  val encoder = Composer.WithSeparator('-')

  def unapply(arg: String): Option[ScheduleBucketId] = arg match {
    case encoder(scheduleName :: scheduleBucket :: Nil) => Some(ScheduleBucketId(scheduleName, scheduleBucket))
    case _                                              => None

  }

  implicit val keyEncoder: KeyCodec[ScheduleBucketId] = KeyCodec.instance[ScheduleBucketId](
    {
      case ScheduleBucketId(scheduleName, scheduleBucket) =>
        encoder(scheduleName, scheduleBucket)
    },
    {
      case encoder(scheduleName :: scheduleBucket :: Nil) =>
        Some(ScheduleBucketId(scheduleName, scheduleBucket))
      case _                                              => None
    }
  )

}
