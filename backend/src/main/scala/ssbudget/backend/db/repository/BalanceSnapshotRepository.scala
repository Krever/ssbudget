package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

import java.time.Instant

trait BalanceSnapshotRepository {
  def create(snapshot: BalanceSnapshot): IO[Unit]
  def findById(id: BalanceSnapshotId): IO[Option[BalanceSnapshot]]
  def findByAccount(accountId: AccountId): IO[List[BalanceSnapshot]]
  def delete(id: BalanceSnapshotId): IO[Unit]

  /** Every record of every account, oldest first, in one read. */
  def findAll: IO[List[BalanceSnapshot]]

  /** What is needed to know the balances from `from` on: every record from that moment, and for each account its last record before it (the balance
    * that was in force at `from`). Oldest first. The history grows by some records at each sync, and the daily record asks only about the open
    * period: it must not read the years before.
    */
  def findFrom(from: Instant): IO[List[BalanceSnapshot]]
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

  override def delete(id: BalanceSnapshotId): IO[Unit] = {
    sql"""
      DELETE FROM balance_snapshots WHERE id = $id
    """.update.run.transact(xa).void
  }

  override def findAll: IO[List[BalanceSnapshot]] =
    sql"SELECT id, account_id, amount, currency, recorded_at FROM balance_snapshots ORDER BY recorded_at"
      .query[BalanceSnapshot]
      .to[List]
      .transact(xa)

  override def findFrom(from: Instant): IO[List[BalanceSnapshot]] =
    sql"""
      SELECT id, account_id, amount, currency, recorded_at FROM (
        SELECT id, account_id, amount, currency, recorded_at FROM balance_snapshots WHERE recorded_at >= $from
        UNION ALL
        SELECT b.id, b.account_id, b.amount, b.currency, b.recorded_at
        FROM balance_snapshots b
        WHERE b.recorded_at < $from
          AND b.recorded_at = (SELECT MAX(o.recorded_at) FROM balance_snapshots o WHERE o.account_id = b.account_id AND o.recorded_at < $from)
      )
      ORDER BY recorded_at
    """.query[BalanceSnapshot].to[List].transact(xa)
}
