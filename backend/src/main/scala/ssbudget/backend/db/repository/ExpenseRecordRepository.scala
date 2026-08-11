package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

trait ExpenseRecordRepository {
  def create(record: ExpenseRecord): IO[Unit]
  def findById(id: ExpenseRecordId): IO[Option[ExpenseRecord]]
  def findByPeriod(periodId: PeriodId): IO[List[ExpenseRecord]]
  def findByPeriodAndExpense(periodId: PeriodId, expenseDefId: ExpenseDefId): IO[Option[ExpenseRecord]]

  /** Persist a record's payment progress (paid total, timestamp, settled). Takes the whole record so the accumulate rule stays in
    * [[ExpenseRecord.withPayment]] / [[ExpenseRecord.cleared]] rather than being half-expressed in a repository signature.
    */
  def savePayment(record: ExpenseRecord): IO[Unit]
}

class ExpenseRecordRepositoryImpl(xa: Transactor[IO]) extends ExpenseRecordRepository {

  private val columns = fr"id, period_id, expense_def_id, paid_amount, paid_at, settled"

  override def create(record: ExpenseRecord): IO[Unit] = {
    sql"""
      INSERT INTO expense_records (id, period_id, expense_def_id, paid_amount, paid_at, settled)
      VALUES (${record.id}, ${record.periodId}, ${record.expenseDefId}, ${record.paidAmount}, ${record.paidAt}, ${record.settled})
    """.update.run.transact(xa).void
  }

  override def findById(id: ExpenseRecordId): IO[Option[ExpenseRecord]] =
    (fr"SELECT" ++ columns ++ fr"FROM expense_records WHERE id = $id").query[ExpenseRecord].option.transact(xa)

  override def findByPeriod(periodId: PeriodId): IO[List[ExpenseRecord]] =
    (fr"SELECT" ++ columns ++ fr"FROM expense_records WHERE period_id = $periodId").query[ExpenseRecord].to[List].transact(xa)

  override def findByPeriodAndExpense(periodId: PeriodId, expenseDefId: ExpenseDefId): IO[Option[ExpenseRecord]] =
    (fr"SELECT" ++ columns ++ fr"FROM expense_records WHERE period_id = $periodId AND expense_def_id = $expenseDefId")
      .query[ExpenseRecord]
      .option
      .transact(xa)

  override def savePayment(record: ExpenseRecord): IO[Unit] = {
    sql"""
      UPDATE expense_records
      SET paid_amount = ${record.paidAmount}, paid_at = ${record.paidAt}, settled = ${record.settled}
      WHERE id = ${record.id}
    """.update.run.transact(xa).void
  }
}
