package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

trait SavingsAccountRepository {
  def create(account: SavingsAccount): IO[Unit]
  def findById(id: SavingsAccountId): IO[Option[SavingsAccount]]
  def findAll: IO[List[SavingsAccount]]
  def update(account: SavingsAccount): IO[Unit]
  def updateBalance(id: SavingsAccountId, newBalance: Long): IO[Unit]
  def delete(id: SavingsAccountId): IO[Unit]
}

class SavingsAccountRepositoryImpl(xa: Transactor[IO]) extends SavingsAccountRepository {

  override def create(account: SavingsAccount): IO[Unit] = {
    sql"""
      INSERT INTO savings_accounts (id, name, currency, current_balance, planned_monthly)
      VALUES (${account.id}, ${account.name}, ${account.currency}, ${account.currentBalance}, ${account.plannedMonthly})
    """.update.run.transact(xa).void
  }

  override def findById(id: SavingsAccountId): IO[Option[SavingsAccount]] = {
    sql"""
      SELECT id, name, currency, current_balance, planned_monthly
      FROM savings_accounts WHERE id = $id
    """.query[SavingsAccount].option.transact(xa)
  }

  override def findAll: IO[List[SavingsAccount]] = {
    sql"""
      SELECT id, name, currency, current_balance, planned_monthly
      FROM savings_accounts ORDER BY name
    """.query[SavingsAccount].to[List].transact(xa)
  }

  override def update(account: SavingsAccount): IO[Unit] = {
    sql"""
      UPDATE savings_accounts
      SET name = ${account.name}, currency = ${account.currency},
          current_balance = ${account.currentBalance}, planned_monthly = ${account.plannedMonthly}
      WHERE id = ${account.id}
    """.update.run.transact(xa).void
  }

  override def updateBalance(id: SavingsAccountId, newBalance: Long): IO[Unit] = {
    sql"""
      UPDATE savings_accounts SET current_balance = $newBalance WHERE id = $id
    """.update.run.transact(xa).void
  }

  override def delete(id: SavingsAccountId): IO[Unit] = {
    sql"""
      DELETE FROM savings_accounts WHERE id = $id
    """.update.run.transact(xa).void
  }
}
