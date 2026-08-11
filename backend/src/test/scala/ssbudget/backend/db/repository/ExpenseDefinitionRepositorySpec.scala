package ssbudget.backend.db.repository

import ssbudget.shared.model.*

class ExpenseDefinitionRepositorySpec extends RepositorySpec {

  "create and findById returns the budget item definition" in {
    val repo = new ExpenseDefinitionRepositoryImpl(xa)
    val item = BudgetItemDefinition(ExpenseDefId("exp-1"), "Rent", BudgetItemType.PlannedExpense, 200000L, Currency.PLN)

    for {
      _     <- repo.create(item)
      found <- repo.findById(ExpenseDefId("exp-1"))
    } yield found shouldBe Some(item)
  }

  "findById returns None for non-existent item" in {
    val repo = new ExpenseDefinitionRepositoryImpl(xa)

    for {
      found <- repo.findById(ExpenseDefId("non-existent"))
    } yield found shouldBe None
  }

  "findAll returns all budget item definitions ordered by name" in {
    val repo = new ExpenseDefinitionRepositoryImpl(xa)
    val exp1 = BudgetItemDefinition(ExpenseDefId("exp-1"), "Zebra", BudgetItemType.PlannedExpense, 100L, Currency.PLN)
    val exp2 = BudgetItemDefinition(ExpenseDefId("exp-2"), "Alpha", BudgetItemType.PlannedIncome, 0L, Currency.PLN)
    val exp3 = BudgetItemDefinition(ExpenseDefId("exp-3"), "Beta", BudgetItemType.PlannedIncome, 0L, Currency.PLN)

    for {
      _   <- repo.create(exp1)
      _   <- repo.create(exp2)
      _   <- repo.create(exp3)
      all <- repo.findAll
    } yield all.map(_.name) shouldBe List("Alpha", "Beta", "Zebra")
  }

  "findByType returns only items of that type" in {
    val repo          = new ExpenseDefinitionRepositoryImpl(xa)
    val rent          = BudgetItemDefinition(ExpenseDefId("exp-1"), "Rent", BudgetItemType.PlannedExpense, 100L, Currency.PLN)
    val insurance     = BudgetItemDefinition(ExpenseDefId("exp-2"), "Insurance", BudgetItemType.PlannedExpense, 250L, Currency.PLN)
    val plannedIncome = BudgetItemDefinition(ExpenseDefId("exp-3"), "Salary", BudgetItemType.PlannedIncome, 500000L, Currency.PLN)

    for {
      _               <- repo.create(rent)
      _               <- repo.create(insurance)
      _               <- repo.create(plannedIncome)
      plannedExpenses <- repo.findByType(BudgetItemType.PlannedExpense)
      plannedIncomes  <- repo.findByType(BudgetItemType.PlannedIncome)
    } yield {
      plannedExpenses shouldBe List(insurance, rent) // findByType orders by name
      plannedIncomes shouldBe List(plannedIncome)
    }
  }

  "update modifies budget item definition" in {
    val repo    = new ExpenseDefinitionRepositoryImpl(xa)
    val item    = BudgetItemDefinition(ExpenseDefId("exp-1"), "Old", BudgetItemType.PlannedExpense, 100L, Currency.PLN)
    val updated = item.copy(name = "New", estimateCents = 200L)

    for {
      _     <- repo.create(item)
      _     <- repo.update(updated)
      found <- repo.findById(ExpenseDefId("exp-1"))
    } yield found shouldBe Some(updated)
  }

  "delete removes budget item definition" in {
    val repo = new ExpenseDefinitionRepositoryImpl(xa)
    val item = BudgetItemDefinition(ExpenseDefId("exp-1"), "Test", BudgetItemType.PlannedExpense, 0L, Currency.PLN)

    for {
      _     <- repo.create(item)
      _     <- repo.delete(ExpenseDefId("exp-1"))
      found <- repo.findById(ExpenseDefId("exp-1"))
    } yield found shouldBe None
  }
}
