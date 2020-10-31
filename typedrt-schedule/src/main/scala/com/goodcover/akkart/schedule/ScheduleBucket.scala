package com.goodcover.akkart.schedule

import java.time.LocalDateTime

import cats.tagless.autoFunctorK
import com.dispalt.taglessAkka.akkaEncoder

@akkaEncoder
@autoFunctorK(autoDerivation = false)
trait ScheduleBucket[F[_]] {
  def addScheduleEntry(entryId: String, correlationId: String, dueDate: LocalDateTime): F[Unit]

  def fireEntry(entryId: String): F[Unit]

  def removeEntry(entryId: String): F[Unit]
}

object ScheduleBucket
