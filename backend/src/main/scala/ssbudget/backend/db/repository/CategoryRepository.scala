package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

trait CategoryRepository {
  def create(category: Category): IO[Unit]
  def findAll: IO[List[Category]]
  def findById(id: CategoryId): IO[Option[Category]]
  def update(category: Category): IO[Unit]
  def delete(id: CategoryId): IO[Unit]

  /** Makes ordinary categories of the transfer categories of `account`: for an account that is deleted, or is no longer an investment. */
  def clearTransferAccount(account: AccountId): IO[Unit]
}

class CategoryRepositoryImpl(xa: Transactor[IO]) extends CategoryRepository {

  private val columns =
    fr"id, name, color, budget_type, budget_method, budget_lookback_months, budget_fixed_cents, bill_payments, transfer_account_id"

  override def create(category: Category): IO[Unit] =
    sql"""INSERT INTO categories (id, name, color, budget_type, budget_method, budget_lookback_months, budget_fixed_cents, bill_payments,
                                  transfer_account_id)
          VALUES (${category.id}, ${category.name}, ${category.color}, ${category.budgetType},
                  ${category.budget.method}, ${category.budget.lookbackMonths}, ${category.budget.fixedCents},
                  ${category.billPayments}, ${category.transferAccount})""".update.run
      .transact(xa)
      .void

  override def findAll: IO[List[Category]] =
    (fr"SELECT" ++ columns ++ fr"FROM categories ORDER BY name").query[Category].to[List].transact(xa)

  override def findById(id: CategoryId): IO[Option[Category]] =
    (fr"SELECT" ++ columns ++ fr"FROM categories WHERE id = $id").query[Category].option.transact(xa)

  override def update(category: Category): IO[Unit] =
    sql"""UPDATE categories
          SET name = ${category.name}, color = ${category.color}, budget_type = ${category.budgetType},
              budget_method = ${category.budget.method}, budget_lookback_months = ${category.budget.lookbackMonths},
              budget_fixed_cents = ${category.budget.fixedCents}, bill_payments = ${category.billPayments},
              transfer_account_id = ${category.transferAccount}
          WHERE id = ${category.id}""".update.run
      .transact(xa)
      .void

  override def delete(id: CategoryId): IO[Unit] =
    sql"DELETE FROM categories WHERE id = $id".update.run.transact(xa).void

  override def clearTransferAccount(account: AccountId): IO[Unit] =
    sql"UPDATE categories SET transfer_account_id = NULL WHERE transfer_account_id = $account".update.run.transact(xa).void
}
