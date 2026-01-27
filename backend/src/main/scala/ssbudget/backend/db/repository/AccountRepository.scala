package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

trait AccountRepository {
  def create(account: Account): IO[Unit]
  def findById(id: AccountId): IO[Option[Account]]
  def findAll: IO[List[Account]]
  def update(account: Account): IO[Unit]
  def delete(id: AccountId): IO[Unit]
}

class AccountRepositoryImpl(xa: Transactor[IO]) extends AccountRepository {

  override def create(account: Account): IO[Unit] = {
    sql"""
      INSERT INTO accounts (id, name, currency)
      VALUES (${account.id}, ${account.name}, ${account.currency})
    """.update.run.transact(xa).void
  }

  override def findById(id: AccountId): IO[Option[Account]] = {
    sql"""
      SELECT id, name, currency FROM accounts WHERE id = $id
    """.query[Account].option.transact(xa)
  }

  override def findAll: IO[List[Account]] = {
    sql"""
      SELECT id, name, currency FROM accounts ORDER BY name
    """.query[Account].to[List].transact(xa)
  }

  override def update(account: Account): IO[Unit] = {
    sql"""
      UPDATE accounts SET name = ${account.name}, currency = ${account.currency}
      WHERE id = ${account.id}
    """.update.run.transact(xa).void
  }

  override def delete(id: AccountId): IO[Unit] = {
    sql"""
      DELETE FROM accounts WHERE id = $id
    """.update.run.transact(xa).void
  }
}
