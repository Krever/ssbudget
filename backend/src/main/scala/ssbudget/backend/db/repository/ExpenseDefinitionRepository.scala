package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

trait ExpenseDefinitionRepository {
  def create(expense: ExpenseDefinition): IO[Unit]
  def findById(id: ExpenseDefId): IO[Option[ExpenseDefinition]]
  def findAll: IO[List[ExpenseDefinition]]
  def findByType(expenseType: ExpenseType): IO[List[ExpenseDefinition]]
  def update(expense: ExpenseDefinition): IO[Unit]
  def delete(id: ExpenseDefId): IO[Unit]
}

class ExpenseDefinitionRepositoryImpl(xa: Transactor[IO]) extends ExpenseDefinitionRepository {

  override def create(expense: ExpenseDefinition): IO[Unit] = {
    sql"""
      INSERT INTO expense_definitions (id, name, expense_type, estimate_mode, fixed_estimate, include_in_balance)
      VALUES (${expense.id}, ${expense.name}, ${expense.expenseType}, ${expense.estimateMode},
              ${expense.fixedEstimate}, ${if expense.includeInBalance then 1 else 0})
    """.update.run.transact(xa).void
  }

  override def findById(id: ExpenseDefId): IO[Option[ExpenseDefinition]] = {
    sql"""
      SELECT id, name, expense_type, estimate_mode, fixed_estimate, include_in_balance
      FROM expense_definitions WHERE id = $id
    """
      .query[(ExpenseDefId, String, ExpenseType, EstimateMode, Option[Long], Int)]
      .map { case (id, name, et, em, fe, iib) =>
        ExpenseDefinition(id, name, et, em, fe, iib == 1)
      }
      .option
      .transact(xa)
  }

  override def findAll: IO[List[ExpenseDefinition]] = {
    sql"""
      SELECT id, name, expense_type, estimate_mode, fixed_estimate, include_in_balance
      FROM expense_definitions ORDER BY name
    """
      .query[(ExpenseDefId, String, ExpenseType, EstimateMode, Option[Long], Int)]
      .map { case (id, name, et, em, fe, iib) =>
        ExpenseDefinition(id, name, et, em, fe, iib == 1)
      }
      .to[List]
      .transact(xa)
  }

  override def findByType(expenseType: ExpenseType): IO[List[ExpenseDefinition]] = {
    sql"""
      SELECT id, name, expense_type, estimate_mode, fixed_estimate, include_in_balance
      FROM expense_definitions WHERE expense_type = $expenseType ORDER BY name
    """
      .query[(ExpenseDefId, String, ExpenseType, EstimateMode, Option[Long], Int)]
      .map { case (id, name, et, em, fe, iib) =>
        ExpenseDefinition(id, name, et, em, fe, iib == 1)
      }
      .to[List]
      .transact(xa)
  }

  override def update(expense: ExpenseDefinition): IO[Unit] = {
    sql"""
      UPDATE expense_definitions
      SET name = ${expense.name}, expense_type = ${expense.expenseType},
          estimate_mode = ${expense.estimateMode}, fixed_estimate = ${expense.fixedEstimate},
          include_in_balance = ${if expense.includeInBalance then 1 else 0}
      WHERE id = ${expense.id}
    """.update.run.transact(xa).void
  }

  override def delete(id: ExpenseDefId): IO[Unit] = {
    sql"""
      DELETE FROM expense_definitions WHERE id = $id
    """.update.run.transact(xa).void
  }
}
