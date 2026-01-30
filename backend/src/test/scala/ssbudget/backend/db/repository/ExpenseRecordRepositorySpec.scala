package ssbudget.backend.db.repository

import cats.effect.IO
import ssbudget.shared.model.*

import java.time.Instant

class ExpenseRecordRepositorySpec extends RepositorySpec {

  private def setupPeriodAndExpense(
      periodRepo: PeriodRepository,
      expenseRepo: ExpenseDefinitionRepository,
  ): IO[Unit] = {
    val period  = Period(PeriodId("per-1"), Instant.parse("2024-01-25T00:00:00Z"), None)
    val expense = BudgetItemDefinition(ExpenseDefId("exp-1"), "Rent", BudgetItemType.PlannedExpense, EstimateMode.Fixed, Some(200000L), Currency.PLN)
    periodRepo.create(period) *> expenseRepo.create(expense)
  }

  "create and findById returns the expense record" in {
    val periodRepo  = new PeriodRepositoryImpl(xa)
    val expenseRepo = new ExpenseDefinitionRepositoryImpl(xa)
    val recordRepo  = new ExpenseRecordRepositoryImpl(xa)
    val record      = ExpenseRecord(ExpenseRecordId("rec-1"), PeriodId("per-1"), ExpenseDefId("exp-1"), None, None)

    for {
      _     <- setupPeriodAndExpense(periodRepo, expenseRepo)
      _     <- recordRepo.create(record)
      found <- recordRepo.findById(ExpenseRecordId("rec-1"))
    } yield found shouldBe Some(record)
  }

  "findById returns None for non-existent record" in {
    val recordRepo = new ExpenseRecordRepositoryImpl(xa)

    for {
      found <- recordRepo.findById(ExpenseRecordId("non-existent"))
    } yield found shouldBe None
  }

  "findByPeriod returns all records for that period" in {
    val periodRepo  = new PeriodRepositoryImpl(xa)
    val expenseRepo = new ExpenseDefinitionRepositoryImpl(xa)
    val recordRepo  = new ExpenseRecordRepositoryImpl(xa)

    val period1 = Period(PeriodId("per-1"), Instant.parse("2024-01-25T00:00:00Z"), None)
    val period2 = Period(PeriodId("per-2"), Instant.parse("2024-02-25T00:00:00Z"), None)
    val expense = BudgetItemDefinition(ExpenseDefId("exp-1"), "Rent", BudgetItemType.PlannedExpense, EstimateMode.Fixed, Some(100L), Currency.PLN)
    val record1 = ExpenseRecord(ExpenseRecordId("rec-1"), PeriodId("per-1"), ExpenseDefId("exp-1"), None, None)
    val record2 = ExpenseRecord(ExpenseRecordId("rec-2"), PeriodId("per-2"), ExpenseDefId("exp-1"), None, None)

    for {
      _              <- periodRepo.create(period1)
      _              <- periodRepo.create(period2)
      _              <- expenseRepo.create(expense)
      _              <- recordRepo.create(record1)
      _              <- recordRepo.create(record2)
      period1Records <- recordRepo.findByPeriod(PeriodId("per-1"))
    } yield period1Records shouldBe List(record1)
  }

  "findByPeriodAndExpense returns specific record" in {
    val periodRepo  = new PeriodRepositoryImpl(xa)
    val expenseRepo = new ExpenseDefinitionRepositoryImpl(xa)
    val recordRepo  = new ExpenseRecordRepositoryImpl(xa)
    val record      = ExpenseRecord(ExpenseRecordId("rec-1"), PeriodId("per-1"), ExpenseDefId("exp-1"), None, None)

    for {
      _     <- setupPeriodAndExpense(periodRepo, expenseRepo)
      _     <- recordRepo.create(record)
      found <- recordRepo.findByPeriodAndExpense(PeriodId("per-1"), ExpenseDefId("exp-1"))
    } yield found shouldBe Some(record)
  }

  "markAsPaid updates amount and timestamp" in {
    val periodRepo  = new PeriodRepositoryImpl(xa)
    val expenseRepo = new ExpenseDefinitionRepositoryImpl(xa)
    val recordRepo  = new ExpenseRecordRepositoryImpl(xa)
    val record      = ExpenseRecord(ExpenseRecordId("rec-1"), PeriodId("per-1"), ExpenseDefId("exp-1"), None, None)
    val paidAt      = Instant.parse("2024-02-01T10:00:00Z")
    val paidAmount  = 195000L

    for {
      _     <- setupPeriodAndExpense(periodRepo, expenseRepo)
      _     <- recordRepo.create(record)
      _     <- recordRepo.markAsPaid(ExpenseRecordId("rec-1"), paidAmount, paidAt)
      found <- recordRepo.findById(ExpenseRecordId("rec-1"))
    } yield found shouldBe Some(record.copy(paidAmount = Some(paidAmount), paidAt = Some(paidAt)))
  }

  "delete removes expense record" in {
    val periodRepo  = new PeriodRepositoryImpl(xa)
    val expenseRepo = new ExpenseDefinitionRepositoryImpl(xa)
    val recordRepo  = new ExpenseRecordRepositoryImpl(xa)
    val record      = ExpenseRecord(ExpenseRecordId("rec-1"), PeriodId("per-1"), ExpenseDefId("exp-1"), None, None)

    for {
      _     <- setupPeriodAndExpense(periodRepo, expenseRepo)
      _     <- recordRepo.create(record)
      _     <- recordRepo.delete(ExpenseRecordId("rec-1"))
      found <- recordRepo.findById(ExpenseRecordId("rec-1"))
    } yield found shouldBe None
  }
}
