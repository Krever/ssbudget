package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

trait BalanceSnapshotRepository {
  def create(snapshot: BalanceSnapshot): IO[Unit]
  def findById(id: BalanceSnapshotId): IO[Option[BalanceSnapshot]]
  def findByAccount(accountId: AccountId): IO[List[BalanceSnapshot]]
  def findLatestByAccount(accountId: AccountId): IO[Option[BalanceSnapshot]]
  def findAllLatest: IO[List[BalanceSnapshot]]
  def delete(id: BalanceSnapshotId): IO[Unit]
}

class BalanceSnapshotRepositoryImpl(xa: Transactor[IO]) extends BalanceSnapshotRepository {

  override def create(snapshot: BalanceSnapshot): IO[Unit] = {
    sql"""
      INSERT INTO balance_snapshots (id, account_id, amount, currency, recorded_at)
      VALUES (${snapshot.id}, ${snapshot.accountId}, ${snapshot.amount}, ${snapshot.currency}, ${snapshot.recordedAt})
    """.update.run.transact(xa).void
  }

  override def findById(id: BalanceSnapshotId): IO[Option[BalanceSnapshot]] = {
    sql"""
      SELECT id, account_id, amount, currency, recorded_at
      FROM balance_snapshots WHERE id = $id
    """.query[BalanceSnapshot].option.transact(xa)
  }

  override def findByAccount(accountId: AccountId): IO[List[BalanceSnapshot]] = {
    sql"""
      SELECT id, account_id, amount, currency, recorded_at
      FROM balance_snapshots WHERE account_id = $accountId ORDER BY recorded_at DESC
    """.query[BalanceSnapshot].to[List].transact(xa)
  }

  override def findLatestByAccount(accountId: AccountId): IO[Option[BalanceSnapshot]] = {
    sql"""
      SELECT id, account_id, amount, currency, recorded_at
      FROM balance_snapshots WHERE account_id = $accountId ORDER BY recorded_at DESC LIMIT 1
    """.query[BalanceSnapshot].option.transact(xa)
  }

  override def findAllLatest: IO[List[BalanceSnapshot]] = {
    sql"""
      SELECT b.id, b.account_id, b.amount, b.currency, b.recorded_at
      FROM balance_snapshots b
      INNER JOIN (
        SELECT account_id, MAX(recorded_at) as max_recorded
        FROM balance_snapshots
        GROUP BY account_id
      ) latest ON b.account_id = latest.account_id AND b.recorded_at = latest.max_recorded
    """.query[BalanceSnapshot].to[List].transact(xa)
  }

  override def delete(id: BalanceSnapshotId): IO[Unit] = {
    sql"""
      DELETE FROM balance_snapshots WHERE id = $id
    """.update.run.transact(xa).void
  }
}
