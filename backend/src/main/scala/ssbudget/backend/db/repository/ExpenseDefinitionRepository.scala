package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

trait ExpenseDefinitionRepository {
  def create(expense: BudgetItemDefinition): IO[Unit]
  def findById(id: ExpenseDefId): IO[Option[BudgetItemDefinition]]
  def findAll: IO[List[BudgetItemDefinition]]
  def findByType(itemType: BudgetItemType): IO[List[BudgetItemDefinition]]
  def update(expense: BudgetItemDefinition): IO[Unit]
  def delete(id: ExpenseDefId): IO[Unit]
}

class ExpenseDefinitionRepositoryImpl(xa: Transactor[IO]) extends ExpenseDefinitionRepository {

  override def create(expense: BudgetItemDefinition): IO[Unit] = {
    sql"""
      INSERT INTO expense_definitions (id, name, item_type, estimate_mode, fixed_estimate, currency)
      VALUES (${expense.id}, ${expense.name}, ${expense.itemType}, ${expense.estimateMode}, ${expense.fixedEstimate}, ${expense.currency})
    """.update.run.transact(xa).void
  }

  override def findById(id: ExpenseDefId): IO[Option[BudgetItemDefinition]] = {
    sql"""
      SELECT id, name, item_type, estimate_mode, fixed_estimate, currency
      FROM expense_definitions WHERE id = $id
    """.query[BudgetItemDefinition].option.transact(xa)
  }

  override def findAll: IO[List[BudgetItemDefinition]] = {
    sql"""
      SELECT id, name, item_type, estimate_mode, fixed_estimate, currency
      FROM expense_definitions ORDER BY name
    """.query[BudgetItemDefinition].to[List].transact(xa)
  }

  override def findByType(itemType: BudgetItemType): IO[List[BudgetItemDefinition]] = {
    sql"""
      SELECT id, name, item_type, estimate_mode, fixed_estimate, currency
      FROM expense_definitions WHERE item_type = $itemType ORDER BY name
    """.query[BudgetItemDefinition].to[List].transact(xa)
  }

  override def update(expense: BudgetItemDefinition): IO[Unit] = {
    sql"""
      UPDATE expense_definitions
      SET name = ${expense.name}, item_type = ${expense.itemType},
          estimate_mode = ${expense.estimateMode}, fixed_estimate = ${expense.fixedEstimate},
          currency = ${expense.currency}
      WHERE id = ${expense.id}
    """.update.run.transact(xa).void
  }

  override def delete(id: ExpenseDefId): IO[Unit] = {
    sql"""
      DELETE FROM expense_definitions WHERE id = $id
    """.update.run.transact(xa).void
  }
}
