package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

import java.time.Instant

trait ExpenseRecordRepository {
  def create(record: ExpenseRecord): IO[Unit]
  def findById(id: ExpenseRecordId): IO[Option[ExpenseRecord]]
  def findByPeriod(periodId: PeriodId): IO[List[ExpenseRecord]]
  def findByPeriodAndExpense(periodId: PeriodId, expenseDefId: ExpenseDefId): IO[Option[ExpenseRecord]]
  def markAsPaid(id: ExpenseRecordId, amount: Long, paidAt: Instant): IO[Unit]
  def delete(id: ExpenseRecordId): IO[Unit]
}

class ExpenseRecordRepositoryImpl(xa: Transactor[IO]) extends ExpenseRecordRepository {

  override def create(record: ExpenseRecord): IO[Unit] = {
    sql"""
      INSERT INTO expense_records (id, period_id, expense_def_id, paid_amount, paid_at)
      VALUES (${record.id}, ${record.periodId}, ${record.expenseDefId}, ${record.paidAmount}, ${record.paidAt})
    """.update.run.transact(xa).void
  }

  override def findById(id: ExpenseRecordId): IO[Option[ExpenseRecord]] = {
    sql"""
      SELECT id, period_id, expense_def_id, paid_amount, paid_at
      FROM expense_records WHERE id = $id
    """.query[ExpenseRecord].option.transact(xa)
  }

  override def findByPeriod(periodId: PeriodId): IO[List[ExpenseRecord]] = {
    sql"""
      SELECT id, period_id, expense_def_id, paid_amount, paid_at
      FROM expense_records WHERE period_id = $periodId
    """.query[ExpenseRecord].to[List].transact(xa)
  }

  override def findByPeriodAndExpense(periodId: PeriodId, expenseDefId: ExpenseDefId): IO[Option[ExpenseRecord]] = {
    sql"""
      SELECT id, period_id, expense_def_id, paid_amount, paid_at
      FROM expense_records WHERE period_id = $periodId AND expense_def_id = $expenseDefId
    """.query[ExpenseRecord].option.transact(xa)
  }

  override def markAsPaid(id: ExpenseRecordId, amount: Long, paidAt: Instant): IO[Unit] = {
    sql"""
      UPDATE expense_records SET paid_amount = $amount, paid_at = $paidAt WHERE id = $id
    """.update.run.transact(xa).void
  }

  override def delete(id: ExpenseRecordId): IO[Unit] = {
    sql"""
      DELETE FROM expense_records WHERE id = $id
    """.update.run.transact(xa).void
  }
}
