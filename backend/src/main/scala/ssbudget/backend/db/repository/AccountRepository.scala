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
  def findByRole(role: AccountRole): IO[List[Account]]
  def update(account: Account): IO[Unit]
  def delete(id: AccountId): IO[Unit]
  def existsWithCurrency(currency: Currency): IO[Boolean]

  /** The single balance-write path: set the current balance + provenance and append a history snapshot, atomically. */
  def setBalance(id: AccountId, cents: Long, source: BalanceSource, at: Instant): IO[Unit]

  /** Apply a delta to the balance (used by savings transactions, whose own rows are the history). Returns the updated account. */
  def adjustBalance(id: AccountId, delta: Long, at: Instant): IO[Option[Account]]

  /** Change only the provenance (e.g. reverting to Manual when a bank link is detached). */
  def setBalanceSource(id: AccountId, source: BalanceSource): IO[Unit]
}

class AccountRepositoryImpl(xa: Transactor[IO]) extends AccountRepository {

  private val columns = fr"id, name, currency, role, balance_cents, savings_target, balance_source, balance_updated_at"

  override def create(account: Account): IO[Unit] = {
    sql"""
      INSERT INTO accounts (id, name, currency, role, balance_cents, savings_target, balance_source, balance_updated_at)
      VALUES (${account.id}, ${account.name}, ${account.currency}, ${account.role}, ${account.balanceCents},
              ${account.savingsTarget}, ${account.balanceSource}, ${account.balanceUpdatedAt})
    """.update.run.transact(xa).void
  }

  override def findById(id: AccountId): IO[Option[Account]] =
    findByIdC(id).transact(xa)

  override def findAll: IO[List[Account]] =
    (fr"SELECT" ++ columns ++ fr"FROM accounts ORDER BY name").query[Account].to[List].transact(xa)

  override def findByRole(role: AccountRole): IO[List[Account]] =
    (fr"SELECT" ++ columns ++ fr"FROM accounts WHERE role = $role ORDER BY name").query[Account].to[List].transact(xa)

  override def update(account: Account): IO[Unit] = {
    sql"""
      UPDATE accounts
      SET name = ${account.name}, currency = ${account.currency}, savings_target = ${account.savingsTarget}
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

  override def adjustBalance(id: AccountId, delta: Long, at: Instant): IO[Option[Account]] = {
    val program = for {
      _   <- sql"UPDATE accounts SET balance_cents = balance_cents + $delta, balance_updated_at = $at WHERE id = $id".update.run
      acc <- findByIdC(id)
    } yield acc
    program.transact(xa)
  }

  override def setBalanceSource(id: AccountId, source: BalanceSource): IO[Unit] =
    sql"UPDATE accounts SET balance_source = $source WHERE id = $id".update.run.transact(xa).void
}
