package com.goodcover.typedrt

import java.util.UUID

import akka.actor.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{NoOffset, Offset, PersistenceQuery, TimeBasedUUID}
import com.datastax.driver.core.utils.UUIDs

final class CassandraJournalAdapter(system: ActorSystem, val writeJournalId: String, readJournalId: String)
    extends JournalAdapter[UUID] {

  override def createReadJournal: CassandraReadJournal =
    PersistenceQuery(system).readJournalFor[CassandraReadJournal](readJournalId)

  override val journalOffset: OffsetAdapter = new OffsetAdapter {

    override def unapply(arg: Offset): Option[UUID] = arg match {
      case TimeBasedUUID(offsetValue) => Some(offsetValue)
      case _                          => None
    }

    override def apply(value: Option[UUID]): Offset = value match {
      case Some(x) => TimeBasedUUID(x)
      case None    => NoOffset
    }
  }

  override def offsetUuid(timestamp: Long): UUID = createReadJournal.offsetUuid(timestamp)

  override def uuidOffset(a: UUID): Long = UUIDs.unixTimestamp(a)
}

object CassandraJournalAdapter {

  def apply(
    system: ActorSystem,
    writeJournalId: String = "cassandra-journal",
    readJournalId: String = CassandraReadJournal.Identifier
  ): JournalAdapter[UUID] =
    new CassandraJournalAdapter(system, writeJournalId, readJournalId)
}
