package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

trait CardGroupRepository {
  def create(group: CardGroup): IO[Unit]
  def findAll: IO[List[CardGroup]]
  def findById(id: CardGroupId): IO[Option[CardGroup]]
  def findByAccount(accountId: AccountId): IO[Option[CardGroup]]
  def setAccount(id: CardGroupId, accountId: Option[AccountId]): IO[Unit]
  def delete(id: CardGroupId): IO[Unit]
}

class CardGroupRepositoryImpl(xa: Transactor[IO]) extends CardGroupRepository {

  private val columns = fr"id, name, limit_cents, currency, account_id"

  override def create(group: CardGroup): IO[Unit] = {
    sql"""
      INSERT INTO card_groups (id, name, limit_cents, currency, account_id)
      VALUES (${group.id}, ${group.name}, ${group.limitCents}, ${group.currency}, ${group.accountId})
    """.update.run.transact(xa).void
  }

  override def findAll: IO[List[CardGroup]] =
    (fr"SELECT" ++ columns ++ fr"FROM card_groups ORDER BY name").query[CardGroup].to[List].transact(xa)

  override def findById(id: CardGroupId): IO[Option[CardGroup]] =
    (fr"SELECT" ++ columns ++ fr"FROM card_groups WHERE id = $id").query[CardGroup].option.transact(xa)

  override def findByAccount(accountId: AccountId): IO[Option[CardGroup]] =
    (fr"SELECT" ++ columns ++ fr"FROM card_groups WHERE account_id = $accountId").query[CardGroup].option.transact(xa)

  override def setAccount(id: CardGroupId, accountId: Option[AccountId]): IO[Unit] =
    sql"UPDATE card_groups SET account_id = $accountId WHERE id = $id".update.run.transact(xa).void

  override def delete(id: CardGroupId): IO[Unit] =
    sql"DELETE FROM card_groups WHERE id = $id".update.run.transact(xa).void
}
