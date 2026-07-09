package ssbudget.backend.db.repository

import ssbudget.shared.model.*

import java.time.Instant

class BankConnectionRepositorySpec extends RepositorySpec {

  private val now = Instant.parse("2026-01-15T10:00:00Z")

  private def connection(
      id: String,
      status: ConnectionStatus = ConnectionStatus.Pending,
      authState: Option[String] = Some("state-1"),
  ): BankConnection =
    BankConnection(BankConnectionId(id), "PKO", "PL", sessionId = None, status = status, validUntil = None, authState = authState, createdAt = now)

  private def link(id: String, connId: String, uid: String, target: BankLinkTarget): BankAccountLink =
    BankAccountLink(
      BankAccountLinkId(id),
      BankConnectionId(connId),
      uid,
      target,
      iban = None,
      name = None,
      currency = None,
      product = None,
      lastBalanceCents = None,
      lastBalanceCurrency = None,
      lastSyncedAt = None,
    )

  "connection create/findById/update/delete round-trip" in {
    val repo = new BankConnectionRepositoryImpl(xa)
    val conn = connection("c-1")
    for {
      _      <- repo.create(conn)
      found  <- repo.findById(BankConnectionId("c-1"))
      updated = conn.copy(sessionId = Some("sess-1"), status = ConnectionStatus.Active, authState = None, validUntil = Some(now))
      _      <- repo.update(updated)
      found2 <- repo.findById(BankConnectionId("c-1"))
      _      <- repo.delete(BankConnectionId("c-1"))
      gone   <- repo.findById(BankConnectionId("c-1"))
    } yield {
      found shouldBe Some(conn)
      found2 shouldBe Some(updated)
      gone shouldBe None
    }
  }

  "findByAuthState finds the pending connection by its CSRF nonce" in {
    val repo = new BankConnectionRepositoryImpl(xa)
    for {
      _     <- repo.create(connection("c-1", authState = Some("nonce-xyz")))
      found <- repo.findByAuthState("nonce-xyz")
      none  <- repo.findByAuthState("other")
    } yield {
      found.map(_.id) shouldBe Some(BankConnectionId("c-1"))
      none shouldBe None
    }
  }

  "BankLinkTarget round-trips through the two-column encoding for every variant" in {
    val repo = new BankConnectionRepositoryImpl(xa)
    val l0   = link("l-0", "c-1", "uid-0", BankLinkTarget.Unlinked)
    val l1   = link("l-1", "c-1", "uid-1", BankLinkTarget.Account(AccountId("acc-9")))
    val l2   = link("l-2", "c-1", "uid-2", BankLinkTarget.CardGroupMember(CardGroupId("grp-9")))
    for {
      _  <- repo.create(connection("c-1"))
      _  <- repo.createLink(l0)
      _  <- repo.createLink(l1)
      _  <- repo.createLink(l2)
      f0 <- repo.findLinkById(BankAccountLinkId("l-0"))
      f1 <- repo.findLinkById(BankAccountLinkId("l-1"))
      f2 <- repo.findLinkById(BankAccountLinkId("l-2"))
    } yield {
      f0.map(_.target) shouldBe Some(BankLinkTarget.Unlinked)
      f1.map(_.target) shouldBe Some(BankLinkTarget.Account(AccountId("acc-9")))
      f2.map(_.target) shouldBe Some(BankLinkTarget.CardGroupMember(CardGroupId("grp-9")))
    }
  }

  "updateLinkTarget changes the target" in {
    val repo = new BankConnectionRepositoryImpl(xa)
    for {
      _     <- repo.create(connection("c-1"))
      _     <- repo.createLink(link("l-1", "c-1", "uid-1", BankLinkTarget.Unlinked))
      _     <- repo.updateLinkTarget(BankAccountLinkId("l-1"), BankLinkTarget.Account(AccountId("acc-1")))
      found <- repo.findLinkById(BankAccountLinkId("l-1"))
    } yield found.map(_.target) shouldBe Some(BankLinkTarget.Account(AccountId("acc-1")))
  }

  "findLinkByAccount / findLinksByCardGroup / findLinksByConnection filter by target" in {
    val repo = new BankConnectionRepositoryImpl(xa)
    for {
      _       <- repo.create(connection("c-1"))
      _       <- repo.createLink(link("l-1", "c-1", "uid-1", BankLinkTarget.Account(AccountId("acc-1"))))
      _       <- repo.createLink(link("l-2", "c-1", "uid-2", BankLinkTarget.CardGroupMember(CardGroupId("grp-1"))))
      _       <- repo.createLink(link("l-3", "c-1", "uid-3", BankLinkTarget.CardGroupMember(CardGroupId("grp-1"))))
      byAcc   <- repo.findLinkByAccount(AccountId("acc-1"))
      byAccNo <- repo.findLinkByAccount(AccountId("acc-x"))
      byGroup <- repo.findLinksByCardGroup(CardGroupId("grp-1"))
      byConn  <- repo.findLinksByConnection(BankConnectionId("c-1"))
    } yield {
      byAcc.map(_.id) shouldBe Some(BankAccountLinkId("l-1"))
      byAccNo shouldBe None
      byGroup.map(_.id.value).toSet shouldBe Set("l-2", "l-3")
      byConn.map(_.id.value).toSet shouldBe Set("l-1", "l-2", "l-3")
    }
  }

  "clearCardGroupMembers detaches only that group's members" in {
    val repo = new BankConnectionRepositoryImpl(xa)
    for {
      _  <- repo.create(connection("c-1"))
      _  <- repo.createLink(link("l-1", "c-1", "uid-1", BankLinkTarget.CardGroupMember(CardGroupId("grp-1"))))
      _  <- repo.createLink(link("l-2", "c-1", "uid-2", BankLinkTarget.CardGroupMember(CardGroupId("grp-2"))))
      _  <- repo.clearCardGroupMembers(CardGroupId("grp-1"))
      f1 <- repo.findLinkById(BankAccountLinkId("l-1"))
      f2 <- repo.findLinkById(BankAccountLinkId("l-2"))
    } yield {
      f1.map(_.target) shouldBe Some(BankLinkTarget.Unlinked)
      f2.map(_.target) shouldBe Some(BankLinkTarget.CardGroupMember(CardGroupId("grp-2")))
    }
  }

  "updateLinkSynced persists metadata and the balance cache (typed currency)" in {
    val repo     = new BankConnectionRepositoryImpl(xa)
    val original = link("l-1", "c-1", "uid-1", BankLinkTarget.Account(AccountId("acc-1")))
    val synced   = original.copy(
      name = Some("Main"),
      iban = Some("PL123"),
      product = Some("Current"),
      currency = Some(Currency.EUR),
      lastBalanceCents = Some(123456),
      lastBalanceCurrency = Some(Currency.EUR),
      lastSyncedAt = Some(now),
    )
    for {
      _     <- repo.create(connection("c-1"))
      _     <- repo.createLink(original)
      _     <- repo.updateLinkSynced(synced)
      found <- repo.findLinkById(BankAccountLinkId("l-1"))
    } yield found shouldBe Some(synced)
  }

  "deleteLinksByConnection removes all links for the connection" in {
    val repo = new BankConnectionRepositoryImpl(xa)
    for {
      _    <- repo.create(connection("c-1"))
      _    <- repo.createLink(link("l-1", "c-1", "uid-1", BankLinkTarget.Unlinked))
      _    <- repo.createLink(link("l-2", "c-1", "uid-2", BankLinkTarget.Unlinked))
      _    <- repo.deleteLinksByConnection(BankConnectionId("c-1"))
      left <- repo.findLinksByConnection(BankConnectionId("c-1"))
    } yield left shouldBe empty
  }
}
