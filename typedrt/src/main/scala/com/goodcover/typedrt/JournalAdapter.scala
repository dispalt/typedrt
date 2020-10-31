package com.goodcover.typedrt

import java.util.UUID

import akka.persistence.query.Offset
import akka.persistence.query.scaladsl.{
  CurrentEventsByPersistenceIdQuery,
  CurrentEventsByTagQuery,
  EventsByPersistenceIdQuery,
  EventsByTagQuery
}

abstract class JournalAdapter[A] {

  abstract class OffsetAdapter {
    def unapply(arg: Offset): Option[A]
    def apply(value: Option[A]): Offset
  }
  def writeJournalId: String

  def createReadJournal: EventsByTagQuery
    with CurrentEventsByTagQuery
    with EventsByPersistenceIdQuery
    with CurrentEventsByPersistenceIdQuery
  val journalOffset: OffsetAdapter

  def offsetUuid(timestamp: Long): A
  def uuidOffset(a: A): Long

}
