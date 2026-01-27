package ssbudget.backend.db.repository

import cats.effect.IO
import ssbudget.shared.model.*

class ExpenseDefinitionRepositorySpec extends RepositorySpec {

  "create and findById returns the expense definition" in {
    val repo    = new ExpenseDefinitionRepositoryImpl(xa)
    val expense = ExpenseDefinition(
      ExpenseDefId("exp-1"),
      "Rent",
      ExpenseType.Planned,
      EstimateMode.Fixed,
      Some(200000L),
      includeInBalance = true,
    )

    for {
      _     <- repo.create(expense)
      found <- repo.findById(ExpenseDefId("exp-1"))
    } yield found shouldBe Some(expense)
  }

  "findById returns None for non-existent expense" in {
    val repo = new ExpenseDefinitionRepositoryImpl(xa)

    for {
      found <- repo.findById(ExpenseDefId("non-existent"))
    } yield found shouldBe None
  }

  "findAll returns all expense definitions ordered by name" in {
    val repo = new ExpenseDefinitionRepositoryImpl(xa)
    val exp1 = ExpenseDefinition(ExpenseDefId("exp-1"), "Zebra", ExpenseType.Planned, EstimateMode.Fixed, Some(100L), true)
    val exp2 = ExpenseDefinition(ExpenseDefId("exp-2"), "Alpha", ExpenseType.Estimated, EstimateMode.Average, None, true)
    val exp3 = ExpenseDefinition(ExpenseDefId("exp-3"), "Beta", ExpenseType.Planned, EstimateMode.LastMonth, None, false)

    for {
      _   <- repo.create(exp1)
      _   <- repo.create(exp2)
      _   <- repo.create(exp3)
      all <- repo.findAll
    } yield all.map(_.name) shouldBe List("Alpha", "Beta", "Zebra")
  }

  "findByType returns only expenses of that type" in {
    val repo      = new ExpenseDefinitionRepositoryImpl(xa)
    val planned   = ExpenseDefinition(ExpenseDefId("exp-1"), "Rent", ExpenseType.Planned, EstimateMode.Fixed, Some(100L), true)
    val estimated =
      ExpenseDefinition(ExpenseDefId("exp-2"), "Groceries", ExpenseType.Estimated, EstimateMode.Average, None, true)

    for {
      _             <- repo.create(planned)
      _             <- repo.create(estimated)
      plannedOnly   <- repo.findByType(ExpenseType.Planned)
      estimatedOnly <- repo.findByType(ExpenseType.Estimated)
    } yield {
      plannedOnly shouldBe List(planned)
      estimatedOnly shouldBe List(estimated)
    }
  }

  "update modifies expense definition" in {
    val repo    = new ExpenseDefinitionRepositoryImpl(xa)
    val expense = ExpenseDefinition(ExpenseDefId("exp-1"), "Old", ExpenseType.Planned, EstimateMode.Fixed, Some(100L), true)
    val updated = expense.copy(name = "New", fixedEstimate = Some(200L), includeInBalance = false)

    for {
      _     <- repo.create(expense)
      _     <- repo.update(updated)
      found <- repo.findById(ExpenseDefId("exp-1"))
    } yield found shouldBe Some(updated)
  }

  "delete removes expense definition" in {
    val repo    = new ExpenseDefinitionRepositoryImpl(xa)
    val expense = ExpenseDefinition(ExpenseDefId("exp-1"), "Test", ExpenseType.Planned, EstimateMode.Fixed, None, true)

    for {
      _     <- repo.create(expense)
      _     <- repo.delete(ExpenseDefId("exp-1"))
      found <- repo.findById(ExpenseDefId("exp-1"))
    } yield found shouldBe None
  }
}
