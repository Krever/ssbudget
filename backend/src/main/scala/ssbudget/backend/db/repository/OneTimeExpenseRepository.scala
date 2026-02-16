package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

trait OneTimeExpenseRepository {
  def create(expense: OneTimeExpense): IO[Unit]
  def findById(id: OneTimeExpenseId): IO[Option[OneTimeExpense]]
  def findAll: IO[List[OneTimeExpense]]
  def update(expense: OneTimeExpense): IO[Unit]
  def delete(id: OneTimeExpenseId): IO[Unit]
}

class OneTimeExpenseRepositoryImpl(xa: Transactor[IO]) extends OneTimeExpenseRepository {

  override def create(expense: OneTimeExpense): IO[Unit] = {
    sql"""
      INSERT INTO one_time_expenses (id, name, amount_cents, currency, date)
      VALUES (${expense.id}, ${expense.name}, ${expense.amountCents}, ${expense.currency}, ${expense.date})
    """.update.run.transact(xa).void
  }

  override def findById(id: OneTimeExpenseId): IO[Option[OneTimeExpense]] = {
    sql"""
      SELECT id, name, amount_cents, currency, date
      FROM one_time_expenses WHERE id = $id
    """.query[OneTimeExpense].option.transact(xa)
  }

  override def findAll: IO[List[OneTimeExpense]] = {
    sql"""
      SELECT id, name, amount_cents, currency, date
      FROM one_time_expenses ORDER BY date DESC
    """.query[OneTimeExpense].to[List].transact(xa)
  }

  override def update(expense: OneTimeExpense): IO[Unit] = {
    sql"""
      UPDATE one_time_expenses
      SET name = ${expense.name}, amount_cents = ${expense.amountCents},
          currency = ${expense.currency}, date = ${expense.date}
      WHERE id = ${expense.id}
    """.update.run.transact(xa).void
  }

  override def delete(id: OneTimeExpenseId): IO[Unit] = {
    sql"""
      DELETE FROM one_time_expenses WHERE id = $id
    """.update.run.transact(xa).void
  }
}
