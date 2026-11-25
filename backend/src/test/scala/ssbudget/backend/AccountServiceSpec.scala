package ssbudget.backend

import cats.effect.IO
import cats.syntax.all.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.backend.db.Repositories
import ssbudget.backend.db.repository.RepositorySpec
import ssbudget.backend.service.{AccountService, AppClock}
import ssbudget.shared.api.{CreateAccount, ErrorKind, UpdateAccount, UpdateAccountBalance}
import ssbudget.shared.model.*

import java.time.Instant

/** The rules of an account: its type, when its currency can still change, and what a bank drives. Each refusal also has its kind, because the page
  * acts on the kind.
  */
class AccountServiceSpec extends RepositorySpec {

  private val now = Instant.parse("2026-03-01T10:00:00Z")
  private val eur = Currency("EUR")

  private def fixture: IO[(AccountService, Repositories)] = {
    val repos = Repositories.fromTransactor(xa)
    sql"INSERT OR IGNORE INTO currency_settings (code, name, is_primary, enabled_at) VALUES ('EUR', 'Euro', 0, $now)".update.run
      .transact(xa)
      .as((new AccountService(repos, AppClock.fixed(now)), repos))
  }

  private def kind[A](result: Either[ssbudget.shared.api.ApiError, A]): Option[ErrorKind] = result.left.toOption.map(_.kind)

  "a new account has a balance of zero on record from the moment it is made" in {
    for {
      (service, repos) <- fixture
      made             <- service.create(CreateAccount("Main", Currency.PLN, AccountRole.Spending))
      account           = made.toOption.get
      history          <- repos.balanceSnapshots.findByAccount(account.id)
    } yield {
      account.balanceUpdatedAt shouldBe Some(now)
      history.map(s => (s.amount, s.recordedAt)) shouldBe List((0L, now))
    }
  }

  "the currency of an account can change only while the account is empty and has no bank link" in {
    for {
      (service, _) <- fixture
      empty        <- service.create(CreateAccount("Empty", Currency.PLN, AccountRole.Savings)).map(_.toOption.get)
      used         <- service.create(CreateAccount("Used", Currency.PLN, AccountRole.Savings)).map(_.toOption.get)
      _            <- service.updateBalance(used.id, UpdateAccountBalance(50000))
      // The balance is zero again, but the history holds an amount in the old currency.
      _            <- service.updateBalance(used.id, UpdateAccountBalance(0))
      linked       <- service.create(CreateAccount("Linked", Currency.PLN, AccountRole.Spending)).map(_.toOption.get)
      _            <- (sql"""INSERT INTO bank_connections (id, aspsp_name, aspsp_country, status, created_at)
                             VALUES ('c1', 'Bank', 'PL', 'active', $now)""".update.run *>
                        sql"""INSERT INTO bank_account_links (id, connection_id, eb_account_uid, link_target_kind, link_target_id)
                              VALUES ('l1', 'c1', 'uid1', 'account', ${linked.id})""".update.run).transact(xa)
      okEmpty      <- service.update(empty.id, UpdateAccount("Empty", eur))
      refusedUsed  <- service.update(used.id, UpdateAccount("Used", eur))
      refusedLink  <- service.update(linked.id, UpdateAccount("Linked", eur))
      renamedUsed  <- service.update(used.id, UpdateAccount("Used, renamed", Currency.PLN))
    } yield {
      okEmpty.map(_.currency) shouldBe Right(eur)
      kind(refusedUsed) shouldBe Some(ErrorKind.Invalid)
      refusedUsed.left.toOption.get.message should include("balance history")
      refusedLink.left.toOption.get.message should include("linked to a bank account")
      // The same account can still get a new name: only the currency is held.
      renamedUsed.map(_.name) shouldBe Right("Used, renamed")
    }
  }

  "an account that a bank or a card group drives is not edited or deleted by hand, and the account of a card group stays a spending account" in {
    for {
      (service, repos) <- fixture
      bank             <- service.create(CreateAccount("Bank", Currency.PLN, AccountRole.Spending)).map(_.toOption.get)
      _                <- repos.accounts.setBalance(bank.id, 10000, BalanceSource.Bank, now)
      card             <- service.create(CreateAccount("Card", Currency.PLN, AccountRole.Spending)).map(_.toOption.get)
      _                <- repos.accounts.setBalance(card.id, 10000, BalanceSource.CardGroup, now)
      balance          <- service.updateBalance(bank.id, UpdateAccountBalance(1))
      delete           <- service.delete(bank.id)
      cardToSavings    <- service.update(card.id, UpdateAccount("Card", Currency.PLN, Some(AccountRole.Savings)))
      bankOnHold       <- service.update(bank.id, UpdateAccount("Bank", Currency.PLN, Some(AccountRole.Holding)))
    } yield {
      kind(balance) shouldBe Some(ErrorKind.Invalid)
      kind(delete) shouldBe Some(ErrorKind.Invalid)
      kind(cardToSavings) shouldBe Some(ErrorKind.Invalid)
      // A bank-driven account can change its type: this is how pay that came early is put on hold.
      bankOnHold.map(_.role) shouldBe Right(AccountRole.Holding)
    }
  }

  "an account that does not exist is 'not found', which is another kind than a refused input" in {
    for {
      (service, _) <- fixture
      update       <- service.update(AccountId("nope"), UpdateAccount("X", Currency.PLN))
      balance      <- service.updateBalance(AccountId("nope"), UpdateAccountBalance(1))
    } yield {
      kind(update) shouldBe Some(ErrorKind.NotFound)
      kind(balance) shouldBe Some(ErrorKind.NotFound)
    }
  }
}
