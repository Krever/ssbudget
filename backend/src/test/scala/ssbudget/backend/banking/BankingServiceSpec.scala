package ssbudget.backend.banking

import cats.effect.IO
import cats.syntax.all.*
import ssbudget.backend.banking.EnableBankingClient.{EbAccountDetails, EbBalance, EbSession}
import ssbudget.backend.db.Repositories
import ssbudget.backend.db.repository.RepositorySpec
import ssbudget.shared.api.{CreateCardGroup, LinkAccountRequest, LinkCardGroupRequest}
import ssbudget.shared.model.*

import java.time.Instant

/** Fake Enable Banking API: returns canned balances/details keyed by account uid, no HTTP. */
class FakeEnableBankingApi(
    balances: Map[String, EbBalance] = Map.empty,
    details: Map[String, EbAccountDetails] = Map.empty,
) extends EnableBankingApi {
  override def listAspsps(country: Option[String])                                    = IO.pure(Right(Nil))
  override def startAuthorization(aspspName: String, aspspCountry: String, s: String) = IO.pure(Right("https://bank.example/redirect"))
  override def createSession(code: String)                                            = IO.pure(Right(EbSession("sess-1", Nil, None)))
  override def getBalances(accountUid: String)                                        = IO.pure(balances.get(accountUid).toRight(s"no balance for $accountUid"))
  override def getAccountDetails(accountUid: String)                                  = IO.pure(details.get(accountUid).toRight(s"no details for $accountUid"))
  override def deleteSession(sessionId: String)                                       = IO.pure(Right(()))
}

class BankingServiceSpec extends RepositorySpec {

  private val now = Instant.parse("2026-01-15T10:00:00Z")

  private def service(client: Option[EnableBankingApi] = None): (BankingService, Repositories) = {
    val repos = Repositories.fromTransactor(xa)
    (new BankingService(repos, client), repos)
  }

  private def account(id: String, currency: Currency = Currency.PLN): Account =
    Account(AccountId(id), s"Account $id", currency, AccountRole.Spending, 0L, None, BalanceSource.Manual, Some(now))

  private def activeConnection(repos: Repositories, id: String): IO[Unit] =
    repos.bankConnections.create(
      BankConnection(BankConnectionId(id), "PKO", "PL", Some("sess-1"), ConnectionStatus.Active, None, None, now),
    )

  private def link(id: String, connId: String, uid: String, currency: Option[Currency] = None): BankAccountLink =
    BankAccountLink(BankAccountLinkId(id), BankConnectionId(connId), uid, BankLinkTarget.Unlinked, None, None, currency, None, None, None, None)

  "linkAccount marks the target account Bank-driven, and unlinking reverts it to Manual" in {
    val (svc, repos) = service()
    for {
      _        <- repos.accounts.create(account("acc-1"))
      _        <- activeConnection(repos, "c-1")
      _        <- repos.bankConnections.createLink(link("l-1", "c-1", "uid-1"))
      _        <- svc.linkAccount(BankAccountLinkId("l-1"), LinkAccountRequest(BankLinkTarget.Account(AccountId("acc-1"))))
      linked   <- repos.accounts.findById(AccountId("acc-1"))
      _        <- svc.linkAccount(BankAccountLinkId("l-1"), LinkAccountRequest(BankLinkTarget.Unlinked))
      unlinked <- repos.accounts.findById(AccountId("acc-1"))
    } yield {
      linked.map(_.balanceSource) shouldBe Some(BalanceSource.Bank)
      unlinked.map(_.balanceSource) shouldBe Some(BalanceSource.Manual)
    }
  }

  "linkAccount rejects a currency mismatch between the bank account and the app account" in {
    val (svc, repos) = service()
    for {
      _   <- repos.accounts.create(account("acc-1", Currency.PLN))
      _   <- activeConnection(repos, "c-1")
      _   <- repos.bankConnections.createLink(link("l-1", "c-1", "uid-1", currency = Some(Currency.EUR)))
      res <- svc.linkAccount(BankAccountLinkId("l-1"), LinkAccountRequest(BankLinkTarget.Account(AccountId("acc-1"))))
    } yield {
      res.isLeft shouldBe true
      res.swap.getOrElse("") should include("Currency mismatch")
    }
  }

  "linkAccount rejects an account already linked to another bank account" in {
    val (svc, repos) = service()
    for {
      _   <- repos.accounts.create(account("acc-1"))
      _   <- activeConnection(repos, "c-1")
      _   <- repos.bankConnections.createLink(link("l-1", "c-1", "uid-1"))
      _   <- repos.bankConnections.createLink(link("l-2", "c-1", "uid-2"))
      _   <- svc.linkAccount(BankAccountLinkId("l-1"), LinkAccountRequest(BankLinkTarget.Account(AccountId("acc-1"))))
      res <- svc.linkAccount(BankAccountLinkId("l-2"), LinkAccountRequest(BankLinkTarget.Account(AccountId("acc-1"))))
    } yield {
      res.isLeft shouldBe true
      res.swap.getOrElse("") should include("already linked")
    }
  }

  "linkAccount rejects linking to an account that mirrors a card group" in {
    val (svc, repos) = service()
    for {
      _   <- repos.accounts.create(account("acc-1"))
      grp <- svc.createCardGroup(CreateCardGroup("G", 100000, Currency.PLN))
      gid  = grp.toOption.get.id
      _   <- svc.linkCardGroup(gid, LinkCardGroupRequest(Some(AccountId("acc-1"))))
      _   <- activeConnection(repos, "c-1")
      _   <- repos.bankConnections.createLink(link("l-1", "c-1", "uid-1"))
      res <- svc.linkAccount(BankAccountLinkId("l-1"), LinkAccountRequest(BankLinkTarget.Account(AccountId("acc-1"))))
    } yield {
      res.isLeft shouldBe true
      res.swap.getOrElse("") should include("card group")
    }
  }

  "linkCardGroup marks the account CardGroup-driven and recompute mirrors the remaining shared limit" in {
    val (svc, repos) = service()
    // Two cards, availables 6983.73 + 4470.60, shared limit 10000.00 -> remaining 1454.33 (145433 cents).
    val members      = List(
      link("l-1", "c-1", "uid-1", Some(Currency.PLN))
        .copy(target = BankLinkTarget.Unlinked, lastBalanceCents = Some(698373), lastBalanceCurrency = Some(Currency.PLN)),
      link("l-2", "c-1", "uid-2", Some(Currency.PLN))
        .copy(target = BankLinkTarget.Unlinked, lastBalanceCents = Some(447060), lastBalanceCurrency = Some(Currency.PLN)),
    )
    for {
      _   <- repos.accounts.create(account("acc-1", Currency.PLN))
      grp <- svc.createCardGroup(CreateCardGroup("PKO cards", 1000000, Currency.PLN))
      gid  = grp.toOption.get.id
      _   <- activeConnection(repos, "c-1")
      _   <- members.traverse_(repos.bankConnections.createLink)
      // Assign both cards to the group.
      _   <- svc.linkAccount(BankAccountLinkId("l-1"), LinkAccountRequest(BankLinkTarget.CardGroupMember(gid)))
      _   <- svc.linkAccount(BankAccountLinkId("l-2"), LinkAccountRequest(BankLinkTarget.CardGroupMember(gid)))
      _   <- svc.linkCardGroup(gid, LinkCardGroupRequest(Some(AccountId("acc-1"))))
      acc <- repos.accounts.findById(AccountId("acc-1"))
    } yield {
      acc.map(_.balanceSource) shouldBe Some(BalanceSource.CardGroup)
      acc.map(_.balanceCents) shouldBe Some(145433)
    }
  }

  "linkCardGroup rejects a currency mismatch with the mirror account" in {
    val (svc, repos) = service()
    for {
      _   <- repos.accounts.create(account("acc-1", Currency.EUR))
      grp <- svc.createCardGroup(CreateCardGroup("G", 100000, Currency.PLN))
      gid  = grp.toOption.get.id
      res <- svc.linkCardGroup(gid, LinkCardGroupRequest(Some(AccountId("acc-1"))))
    } yield {
      res.isLeft shouldBe true
      res.swap.getOrElse("") should include("Currency mismatch")
    }
  }

  "deleteCardGroup detaches members, reverts the mirror account to Manual, and keeps the account" in {
    val (svc, repos) = service()
    for {
      _      <- repos.accounts.create(account("acc-1"))
      grp    <- svc.createCardGroup(CreateCardGroup("G", 100000, Currency.PLN))
      gid     = grp.toOption.get.id
      _      <- activeConnection(repos, "c-1")
      _      <- repos.bankConnections.createLink(link("l-1", "c-1", "uid-1"))
      _      <- svc.linkAccount(BankAccountLinkId("l-1"), LinkAccountRequest(BankLinkTarget.CardGroupMember(gid)))
      _      <- svc.linkCardGroup(gid, LinkCardGroupRequest(Some(AccountId("acc-1"))))
      _      <- svc.deleteCardGroup(gid)
      acc    <- repos.accounts.findById(AccountId("acc-1"))
      member <- repos.bankConnections.findLinkById(BankAccountLinkId("l-1"))
      groups <- svc.listCardGroups
    } yield {
      groups shouldBe empty
      acc.map(_.balanceSource) shouldBe Some(BalanceSource.Manual) // reverted, still exists
      member.map(_.target) shouldBe Some(BankLinkTarget.Unlinked)
    }
  }

  "disconnect reverts directly-linked accounts to Manual and removes the connection" in {
    val (svc, repos) = service()
    for {
      _     <- repos.accounts.create(account("acc-1"))
      _     <- activeConnection(repos, "c-1")
      _     <- repos.bankConnections.createLink(link("l-1", "c-1", "uid-1"))
      _     <- svc.linkAccount(BankAccountLinkId("l-1"), LinkAccountRequest(BankLinkTarget.Account(AccountId("acc-1"))))
      _     <- svc.disconnect(BankConnectionId("c-1"))
      acc   <- repos.accounts.findById(AccountId("acc-1"))
      conns <- svc.listConnections
    } yield {
      conns shouldBe empty
      acc.map(_.balanceSource) shouldBe Some(BalanceSource.Manual)
    }
  }

  "sync applies a matching-currency bank balance to the linked account and caches it on the link" in {
    val fake         = new FakeEnableBankingApi(
      balances = Map("uid-1" -> EbBalance(500000, "PLN")),
      details = Map("uid-1" -> EbAccountDetails(Some("Main"), Some("Current"), Some("PL1"), Some("PLN"))),
    )
    val (svc, repos) = service(Some(fake))
    for {
      _   <- repos.accounts.create(account("acc-1", Currency.PLN))
      _   <- activeConnection(repos, "c-1")
      _   <- repos.bankConnections.createLink(link("l-1", "c-1", "uid-1"))
      _   <- svc.linkAccount(BankAccountLinkId("l-1"), LinkAccountRequest(BankLinkTarget.Account(AccountId("acc-1"))))
      _   <- svc.sync(BankConnectionId("c-1"))
      acc <- repos.accounts.findById(AccountId("acc-1"))
      lnk <- repos.bankConnections.findLinkById(BankAccountLinkId("l-1"))
    } yield {
      acc.map(_.balanceCents) shouldBe Some(500000)
      acc.map(_.balanceSource) shouldBe Some(BalanceSource.Bank)
      lnk.flatMap(_.lastBalanceCents) shouldBe Some(500000)
    }
  }

  "sync does NOT apply a bank balance whose currency differs from the account (no corruption)" in {
    val fake         = new FakeEnableBankingApi(balances = Map("uid-1" -> EbBalance(500000, "EUR")))
    val (svc, repos) = service(Some(fake))
    for {
      _   <- repos.accounts.create(account("acc-1", Currency.PLN))
      _   <- activeConnection(repos, "c-1")
      _   <- repos.bankConnections.createLink(link("l-1", "c-1", "uid-1"))
      _   <- svc.linkAccount(BankAccountLinkId("l-1"), LinkAccountRequest(BankLinkTarget.Account(AccountId("acc-1"))))
      _   <- svc.sync(BankConnectionId("c-1"))
      acc <- repos.accounts.findById(AccountId("acc-1"))
    } yield acc.map(_.balanceCents) shouldBe Some(0) // unchanged
  }
}
