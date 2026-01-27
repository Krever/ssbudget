package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

trait SavingsTransactionRepository {
  def create(transaction: SavingsTransaction): IO[Unit]
  def findById(id: SavingsTransactionId): IO[Option[SavingsTransaction]]
  def findByAccountId(accountId: SavingsAccountId): IO[List[SavingsTransaction]]
  def findByPeriodId(periodId: PeriodId): IO[List[SavingsTransaction]]
  def findByAccountAndPeriod(accountId: SavingsAccountId, periodId: PeriodId): IO[List[SavingsTransaction]]
  def update(transaction: SavingsTransaction): IO[Unit]
  def delete(id: SavingsTransactionId): IO[Unit]
  def deleteByAccountId(accountId: SavingsAccountId): IO[Unit]
}

class SavingsTransactionRepositoryImpl(xa: Transactor[IO]) extends SavingsTransactionRepository {

  override def create(transaction: SavingsTransaction): IO[Unit] = {
    sql"""
      INSERT INTO savings_transactions (id, account_id, period_id, amount, note, created_at)
      VALUES (${transaction.id}, ${transaction.accountId}, ${transaction.periodId},
              ${transaction.amount}, ${transaction.note}, ${transaction.createdAt})
    """.update.run.transact(xa).void
  }

  override def findById(id: SavingsTransactionId): IO[Option[SavingsTransaction]] = {
    sql"""
      SELECT id, account_id, period_id, amount, note, created_at
      FROM savings_transactions WHERE id = $id
    """.query[SavingsTransaction].option.transact(xa)
  }

  override def findByAccountId(accountId: SavingsAccountId): IO[List[SavingsTransaction]] = {
    sql"""
      SELECT id, account_id, period_id, amount, note, created_at
      FROM savings_transactions WHERE account_id = $accountId
      ORDER BY created_at DESC
    """.query[SavingsTransaction].to[List].transact(xa)
  }

  override def findByPeriodId(periodId: PeriodId): IO[List[SavingsTransaction]] = {
    sql"""
      SELECT id, account_id, period_id, amount, note, created_at
      FROM savings_transactions WHERE period_id = $periodId
      ORDER BY created_at DESC
    """.query[SavingsTransaction].to[List].transact(xa)
  }

  override def findByAccountAndPeriod(accountId: SavingsAccountId, periodId: PeriodId): IO[List[SavingsTransaction]] = {
    sql"""
      SELECT id, account_id, period_id, amount, note, created_at
      FROM savings_transactions WHERE account_id = $accountId AND period_id = $periodId
      ORDER BY created_at DESC
    """.query[SavingsTransaction].to[List].transact(xa)
  }

  override def update(transaction: SavingsTransaction): IO[Unit] = {
    sql"""
      UPDATE savings_transactions
      SET account_id = ${transaction.accountId}, period_id = ${transaction.periodId},
          amount = ${transaction.amount}, note = ${transaction.note}, created_at = ${transaction.createdAt}
      WHERE id = ${transaction.id}
    """.update.run.transact(xa).void
  }

  override def delete(id: SavingsTransactionId): IO[Unit] = {
    sql"""
      DELETE FROM savings_transactions WHERE id = $id
    """.update.run.transact(xa).void
  }

  override def deleteByAccountId(accountId: SavingsAccountId): IO[Unit] = {
    sql"""
      DELETE FROM savings_transactions WHERE account_id = $accountId
    """.update.run.transact(xa).void
  }
}
