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
    val expense = BudgetItemDefinition(ExpenseDefId("exp-1"), "Rent", BudgetItemType.PlannedExpense, 200000L, Currency.PLN)
    periodRepo.create(period) *> expenseRepo.create(expense)
  }

  "create and findById returns the expense record" in {
    val periodRepo  = new PeriodRepositoryImpl(xa)
    val expenseRepo = new ExpenseDefinitionRepositoryImpl(xa)
    val recordRepo  = new ExpenseRecordRepositoryImpl(xa)
    val record      = ExpenseRecord(ExpenseRecordId("rec-1"), PeriodId("per-1"), ExpenseDefId("exp-1"), None, None, settled = false)

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
    val expense = BudgetItemDefinition(ExpenseDefId("exp-1"), "Rent", BudgetItemType.PlannedExpense, 100L, Currency.PLN)
    val record1 = ExpenseRecord(ExpenseRecordId("rec-1"), PeriodId("per-1"), ExpenseDefId("exp-1"), None, None, settled = false)
    val record2 = ExpenseRecord(ExpenseRecordId("rec-2"), PeriodId("per-2"), ExpenseDefId("exp-1"), None, None, settled = false)

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
    val record      = ExpenseRecord(ExpenseRecordId("rec-1"), PeriodId("per-1"), ExpenseDefId("exp-1"), None, None, settled = false)

    for {
      _     <- setupPeriodAndExpense(periodRepo, expenseRepo)
      _     <- recordRepo.create(record)
      found <- recordRepo.findByPeriodAndExpense(PeriodId("per-1"), ExpenseDefId("exp-1"))
    } yield found shouldBe Some(record)
  }

  "savePayment stores the paid total, timestamp and settled flag" in {
    val periodRepo  = new PeriodRepositoryImpl(xa)
    val expenseRepo = new ExpenseDefinitionRepositoryImpl(xa)
    val recordRepo  = new ExpenseRecordRepositoryImpl(xa)
    val record      = ExpenseRecord(ExpenseRecordId("rec-1"), PeriodId("per-1"), ExpenseDefId("exp-1"), None, None, settled = false)
    val paidAt      = Instant.parse("2024-02-01T10:00:00Z")
    val paidAmount  = 195000L

    for {
      _     <- setupPeriodAndExpense(periodRepo, expenseRepo)
      _     <- recordRepo.create(record)
      _     <- recordRepo.savePayment(record.withPayment(paidAmount, paidAt, settle = true))
      found <- recordRepo.findById(ExpenseRecordId("rec-1"))
    } yield found shouldBe Some(record.copy(paidAmount = Some(paidAmount), paidAt = Some(paidAt), settled = true))
  }

  "instalments accumulate, and settling later closes the record" in {
    val periodRepo  = new PeriodRepositoryImpl(xa)
    val expenseRepo = new ExpenseDefinitionRepositoryImpl(xa)
    val recordRepo  = new ExpenseRecordRepositoryImpl(xa)
    val record      = ExpenseRecord(ExpenseRecordId("rec-1"), PeriodId("per-1"), ExpenseDefId("exp-1"), None, None, settled = false)
    val firstAt     = Instant.parse("2024-02-01T10:00:00Z")
    val secondAt    = Instant.parse("2024-02-10T10:00:00Z")

    for {
      _       <- setupPeriodAndExpense(periodRepo, expenseRepo)
      _       <- recordRepo.create(record)
      _       <- recordRepo.savePayment(record.withPayment(80000L, firstAt, settle = false))
      partial <- recordRepo.findById(ExpenseRecordId("rec-1"))
      // Pay the remaining 120000 off the round-tripped record: the total must come to 200000, not be overwritten by the last instalment.
      _       <- recordRepo.savePayment(partial.get.withPayment(120000L, secondAt, settle = true))
      settled <- recordRepo.findById(ExpenseRecordId("rec-1"))
    } yield {
      // The stored estimate is 200000, so a part-payment of 80000 leaves 120000 expected.
      partial.map(_.isPartiallyPaid) shouldBe Some(true)
      partial.map(_.remaining(200000L)) shouldBe Some(120000L)
      settled.map(_.paidCents) shouldBe Some(200000L)
      settled.map(_.settled) shouldBe Some(true)
      settled.map(_.remaining(200000L)) shouldBe Some(0L)
    }
  }

  "clearing a record wipes the amount, timestamp and settled flag" in {
    val periodRepo  = new PeriodRepositoryImpl(xa)
    val expenseRepo = new ExpenseDefinitionRepositoryImpl(xa)
    val recordRepo  = new ExpenseRecordRepositoryImpl(xa)
    val record      = ExpenseRecord(ExpenseRecordId("rec-1"), PeriodId("per-1"), ExpenseDefId("exp-1"), None, None, settled = false)

    for {
      _     <- setupPeriodAndExpense(periodRepo, expenseRepo)
      _     <- recordRepo.create(record)
      _     <- recordRepo.savePayment(record.withPayment(195000L, Instant.parse("2024-02-01T10:00:00Z"), settle = true))
      _     <- recordRepo.savePayment(record.cleared)
      found <- recordRepo.findById(ExpenseRecordId("rec-1"))
    } yield found shouldBe Some(record)
  }
}
