package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

import java.time.Instant

trait PeriodRepository {
  def create(period: Period): IO[Unit]
  def findById(id: PeriodId): IO[Option[Period]]
  def findCurrent: IO[Option[Period]]
  def findAll: IO[List[Period]]
  def close(id: PeriodId, endedAt: Instant): IO[Unit]
  def delete(id: PeriodId): IO[Unit]
}

class PeriodRepositoryImpl(xa: Transactor[IO]) extends PeriodRepository {

  override def create(period: Period): IO[Unit] = {
    sql"""
      INSERT INTO periods (id, started_at, ended_at)
      VALUES (${period.id}, ${period.startDate}, ${period.endDate})
    """.update.run.transact(xa).void
  }

  override def findById(id: PeriodId): IO[Option[Period]] = {
    sql"""
      SELECT id, started_at, ended_at FROM periods WHERE id = $id
    """.query[Period].option.transact(xa)
  }

  override def findCurrent: IO[Option[Period]] = {
    sql"""
      SELECT id, started_at, ended_at FROM periods WHERE ended_at IS NULL LIMIT 1
    """.query[Period].option.transact(xa)
  }

  override def findAll: IO[List[Period]] = {
    sql"""
      SELECT id, started_at, ended_at FROM periods ORDER BY started_at DESC
    """.query[Period].to[List].transact(xa)
  }

  override def close(id: PeriodId, endedAt: Instant): IO[Unit] = {
    sql"""
      UPDATE periods SET ended_at = $endedAt WHERE id = $id
    """.update.run.transact(xa).void
  }

  override def delete(id: PeriodId): IO[Unit] = {
    sql"""
      DELETE FROM periods WHERE id = $id
    """.update.run.transact(xa).void
  }
}
