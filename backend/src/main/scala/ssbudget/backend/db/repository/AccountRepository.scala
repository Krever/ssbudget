package ssbudget.backend.db.repository

import cats.effect.IO
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

import java.time.Instant
import java.util.UUID

trait AccountRepository {
  def create(account: Account): IO[Unit]
  def findById(id: AccountId): IO[Option[Account]]
  def findAll: IO[List[Account]]
  def update(account: Account): IO[Unit]
  def delete(id: AccountId): IO[Unit]
  def existsWithCurrency(currency: Currency): IO[Boolean]

  /** The single balance-write path: set the current balance + provenance and append a history snapshot, atomically. */
  def setBalance(id: AccountId, cents: Long, source: BalanceSource, at: Instant): IO[Unit]

  /** Change only the provenance (e.g. reverting to Manual when a bank link is detached). */
  def setBalanceSource(id: AccountId, source: BalanceSource): IO[Unit]

  /** Creates the account and records its balance as the first entry of its history, in ONE transaction. Without that first record, the first balance
    * that is set later would be the baseline of the period, and money put into a new account would never show as a change.
    */
  def createWithOpeningBalance(account: Account, at: Instant): IO[Unit]

  /** Deletes the account with its balance history, in ONE transaction. Its transfer categories become ordinary categories. With separate commits, a
    * failure after the first step left an account with no history.
    */
  def deleteWithHistory(id: AccountId): IO[Unit]
}

class AccountRepositoryImpl(xa: Transactor[IO]) extends AccountRepository {

  private val columns = fr"id, name, currency, role, balance_cents, balance_source, balance_updated_at"

  override def create(account: Account): IO[Unit] = {
    sql"""
      INSERT INTO accounts (id, name, currency, role, balance_cents, balance_source, balance_updated_at)
      VALUES (${account.id}, ${account.name}, ${account.currency}, ${account.role}, ${account.balanceCents},
              ${account.balanceSource}, ${account.balanceUpdatedAt})
    """.update.run.transact(xa).void
  }

  override def findById(id: AccountId): IO[Option[Account]] =
    findByIdC(id).transact(xa)

  override def findAll: IO[List[Account]] =
    (fr"SELECT" ++ columns ++ fr"FROM accounts ORDER BY name").query[Account].to[List].transact(xa)

  override def update(account: Account): IO[Unit] = {
    sql"""
      UPDATE accounts
      SET name = ${account.name}, currency = ${account.currency}, role = ${account.role}
      WHERE id = ${account.id}
    """.update.run.transact(xa).void
  }

  override def delete(id: AccountId): IO[Unit] =
    sql"DELETE FROM accounts WHERE id = $id".update.run.transact(xa).void

  override def existsWithCurrency(currency: Currency): IO[Boolean] =
    sql"SELECT EXISTS(SELECT 1 FROM accounts WHERE currency = $currency)".query[Boolean].unique.transact(xa)

  private def findByIdC(id: AccountId): ConnectionIO[Option[Account]] =
    (fr"SELECT" ++ columns ++ fr"FROM accounts WHERE id = $id").query[Account].option

  override def setBalance(id: AccountId, cents: Long, source: BalanceSource, at: Instant): IO[Unit] = {
    val program = for {
      _   <- sql"UPDATE accounts SET balance_cents = $cents, balance_source = $source, balance_updated_at = $at WHERE id = $id".update.run
      acc <- findByIdC(id)
      _   <- acc.traverse { a =>
               val snapId = BalanceSnapshotId(UUID.randomUUID().toString)
               sql"""INSERT INTO balance_snapshots (id, account_id, amount, currency, recorded_at)
                     VALUES ($snapId, $id, $cents, ${a.currency}, $at)""".update.run
             }
    } yield ()
    program.transact(xa)
  }

  override def createWithOpeningBalance(account: Account, at: Instant): IO[Unit] = {
    val snapId = BalanceSnapshotId(UUID.randomUUID().toString)
    val create = sql"""
      INSERT INTO accounts (id, name, currency, role, balance_cents, balance_source, balance_updated_at)
      VALUES (${account.id}, ${account.name}, ${account.currency}, ${account.role}, ${account.balanceCents},
              ${account.balanceSource}, $at)
    """.update.run
    val record = sql"""INSERT INTO balance_snapshots (id, account_id, amount, currency, recorded_at)
                       VALUES ($snapId, ${account.id}, ${account.balanceCents}, ${account.currency}, $at)""".update.run
    (create *> record).transact(xa).void
  }

  override def deleteWithHistory(id: AccountId): IO[Unit] =
    (sql"DELETE FROM balance_snapshots WHERE account_id = $id".update.run *>
      sql"UPDATE categories SET transfer_account_id = NULL WHERE transfer_account_id = $id".update.run *>
      sql"DELETE FROM accounts WHERE id = $id".update.run).transact(xa).void

  override def setBalanceSource(id: AccountId, source: BalanceSource): IO[Unit] =
    sql"UPDATE accounts SET balance_source = $source WHERE id = $id".update.run.transact(xa).void
}
