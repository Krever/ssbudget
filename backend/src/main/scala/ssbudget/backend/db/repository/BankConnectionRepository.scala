package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

trait BankConnectionRepository {
  def create(conn: BankConnection): IO[Unit]
  def update(conn: BankConnection): IO[Unit]
  def findById(id: BankConnectionId): IO[Option[BankConnection]]
  def findByAuthState(state: String): IO[Option[BankConnection]]
  def findAll: IO[List[BankConnection]]
  def delete(id: BankConnectionId): IO[Unit]

  def createLink(link: BankAccountLink): IO[Unit]
  def findLinkById(id: BankAccountLinkId): IO[Option[BankAccountLink]]
  def findLinksByConnection(id: BankConnectionId): IO[List[BankAccountLink]]
  def findLinksByCardGroup(id: CardGroupId): IO[List[BankAccountLink]]

  /** Any link that mirrors directly onto the given app account (used to derive whether the account is bank-driven). */
  def findLinkByAccount(accountId: AccountId): IO[Option[BankAccountLink]]
  def updateLinkTarget(id: BankAccountLinkId, target: BankLinkTarget): IO[Unit]

  /** Detach every card that was a member of the given group (target -> Unlinked). */
  def clearCardGroupMembers(cardGroupId: CardGroupId): IO[Unit]
  def updateLinkSynced(link: BankAccountLink): IO[Unit]
  def deleteLinksByConnection(id: BankConnectionId): IO[Unit]
}

class BankConnectionRepositoryImpl(xa: Transactor[IO]) extends BankConnectionRepository {

  private val connectionColumns =
    fr"id, aspsp_name, aspsp_country, session_id, status, valid_until, auth_state, created_at"

  override def create(conn: BankConnection): IO[Unit] = {
    sql"""
      INSERT INTO bank_connections (id, aspsp_name, aspsp_country, session_id, status, valid_until, auth_state, created_at)
      VALUES (${conn.id}, ${conn.aspspName}, ${conn.aspspCountry}, ${conn.sessionId}, ${conn.status},
              ${conn.validUntil}, ${conn.authState}, ${conn.createdAt})
    """.update.run.transact(xa).void
  }

  override def update(conn: BankConnection): IO[Unit] = {
    sql"""
      UPDATE bank_connections
      SET aspsp_name = ${conn.aspspName}, aspsp_country = ${conn.aspspCountry}, session_id = ${conn.sessionId},
          status = ${conn.status}, valid_until = ${conn.validUntil}, auth_state = ${conn.authState}
      WHERE id = ${conn.id}
    """.update.run.transact(xa).void
  }

  override def findById(id: BankConnectionId): IO[Option[BankConnection]] = {
    (fr"SELECT" ++ connectionColumns ++ fr"FROM bank_connections WHERE id = $id")
      .query[BankConnection]
      .option
      .transact(xa)
  }

  override def findByAuthState(state: String): IO[Option[BankConnection]] = {
    (fr"SELECT" ++ connectionColumns ++ fr"FROM bank_connections WHERE auth_state = $state")
      .query[BankConnection]
      .option
      .transact(xa)
  }

  override def findAll: IO[List[BankConnection]] = {
    (fr"SELECT" ++ connectionColumns ++ fr"FROM bank_connections ORDER BY created_at DESC")
      .query[BankConnection]
      .to[List]
      .transact(xa)
  }

  override def delete(id: BankConnectionId): IO[Unit] = {
    sql"DELETE FROM bank_connections WHERE id = $id".update.run.transact(xa).void
  }

  private val linkColumns =
    fr"id, connection_id, eb_account_uid, link_target_kind, link_target_id, iban, name, currency, product, last_balance_cents, last_balance_currency, last_synced_at"

  override def createLink(link: BankAccountLink): IO[Unit] = {
    sql"""
      INSERT INTO bank_account_links
        (id, connection_id, eb_account_uid, link_target_kind, link_target_id, iban, name, currency, product, last_balance_cents, last_balance_currency, last_synced_at)
      VALUES (${link.id}, ${link.connectionId}, ${link.ebAccountUid}, ${link.target},
              ${link.iban}, ${link.name}, ${link.currency}, ${link.product},
              ${link.lastBalanceCents}, ${link.lastBalanceCurrency}, ${link.lastSyncedAt})
    """.update.run.transact(xa).void
  }

  override def findLinkById(id: BankAccountLinkId): IO[Option[BankAccountLink]] = {
    (fr"SELECT" ++ linkColumns ++ fr"FROM bank_account_links WHERE id = $id")
      .query[BankAccountLink]
      .option
      .transact(xa)
  }

  override def findLinksByConnection(id: BankConnectionId): IO[List[BankAccountLink]] = {
    (fr"SELECT" ++ linkColumns ++ fr"FROM bank_account_links WHERE connection_id = $id")
      .query[BankAccountLink]
      .to[List]
      .transact(xa)
  }

  override def findLinksByCardGroup(id: CardGroupId): IO[List[BankAccountLink]] = {
    (fr"SELECT" ++ linkColumns ++ fr"FROM bank_account_links WHERE link_target_kind = 'card_group' AND link_target_id = ${id.value}")
      .query[BankAccountLink]
      .to[List]
      .transact(xa)
  }

  override def findLinkByAccount(accountId: AccountId): IO[Option[BankAccountLink]] = {
    (fr"SELECT" ++ linkColumns ++ fr"FROM bank_account_links WHERE link_target_kind = 'account' AND link_target_id = ${accountId.value} LIMIT 1")
      .query[BankAccountLink]
      .option
      .transact(xa)
  }

  override def updateLinkTarget(id: BankAccountLinkId, target: BankLinkTarget): IO[Unit] = {
    val kind = BankLinkTarget.kind(target)
    val tid  = BankLinkTarget.idValue(target)
    sql"UPDATE bank_account_links SET link_target_kind = $kind, link_target_id = $tid WHERE id = $id".update.run.transact(xa).void
  }

  override def clearCardGroupMembers(cardGroupId: CardGroupId): IO[Unit] = {
    sql"""UPDATE bank_account_links SET link_target_kind = 'none', link_target_id = NULL
          WHERE link_target_kind = 'card_group' AND link_target_id = ${cardGroupId.value}""".update.run.transact(xa).void
  }

  override def updateLinkSynced(link: BankAccountLink): IO[Unit] = {
    sql"""
      UPDATE bank_account_links
      SET iban = ${link.iban}, name = ${link.name}, currency = ${link.currency}, product = ${link.product},
          last_balance_cents = ${link.lastBalanceCents}, last_balance_currency = ${link.lastBalanceCurrency}, last_synced_at = ${link.lastSyncedAt}
      WHERE id = ${link.id}
    """.update.run.transact(xa).void
  }

  override def deleteLinksByConnection(id: BankConnectionId): IO[Unit] = {
    sql"DELETE FROM bank_account_links WHERE connection_id = $id".update.run.transact(xa).void
  }
}
