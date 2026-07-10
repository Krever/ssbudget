package ssbudget.backend.db.repository

import ssbudget.shared.model.*

import java.time.Instant

class AccountRepositorySpec extends RepositorySpec {

  "create and findById returns the account" in {
    val repo    = new AccountRepositoryImpl(xa)
    val account = spendingAccount("acc-1", "Main Account", Currency.PLN, 12345)

    for {
      _     <- repo.create(account)
      found <- repo.findById(AccountId("acc-1"))
    } yield found shouldBe Some(account)
  }

  "findById returns None for non-existent account" in {
    val repo = new AccountRepositoryImpl(xa)
    for {
      found <- repo.findById(AccountId("non-existent"))
    } yield found shouldBe None
  }

  "findAll returns all accounts ordered by name" in {
    val repo = new AccountRepositoryImpl(xa)
    val acc1 = spendingAccount("acc-1", "Zebra", Currency.PLN)
    val acc2 = savingsAccount("acc-2", "Alpha", Currency.EUR, 5000, Some(1000))
    val acc3 = spendingAccount("acc-3", "Beta", Currency.PLN)

    for {
      _   <- repo.create(acc1)
      _   <- repo.create(acc2)
      _   <- repo.create(acc3)
      all <- repo.findAll
    } yield all shouldBe List(acc2, acc3, acc1)
  }

  "findByRole returns only accounts of that role" in {
    val repo = new AccountRepositoryImpl(xa)
    val acc1 = spendingAccount("acc-1", "Spending", Currency.PLN)
    val acc2 = savingsAccount("acc-2", "Savings", Currency.PLN, 5000, Some(1000))

    for {
      _        <- repo.create(acc1)
      _        <- repo.create(acc2)
      spending <- repo.findByRole(AccountRole.Spending)
      savings  <- repo.findByRole(AccountRole.Savings)
    } yield {
      spending shouldBe List(acc1)
      savings shouldBe List(acc2)
    }
  }

  "update modifies name, currency and savings target but not balance" in {
    val repo    = new AccountRepositoryImpl(xa)
    val account = savingsAccount("acc-1", "Old Name", Currency.PLN, 100000, None)
    val updated = account.copy(name = "New Name", currency = Currency.EUR, savingsTarget = Some(25000))

    for {
      _     <- repo.create(account)
      _     <- repo.update(updated)
      found <- repo.findById(AccountId("acc-1"))
    } yield found shouldBe Some(updated)
  }

  "setBalance updates balance + provenance and appends a history snapshot" in {
    val repo         = new AccountRepositoryImpl(xa)
    val snapshotRepo = new BalanceSnapshotRepositoryImpl(xa)
    val account      = spendingAccount("acc-1", "Main", Currency.PLN, 0)
    val at           = Instant.parse("2024-02-01T00:00:00Z")

    for {
      _     <- repo.create(account)
      _     <- repo.setBalance(AccountId("acc-1"), 777, BalanceSource.Bank, at)
      found <- repo.findById(AccountId("acc-1"))
      snaps <- snapshotRepo.findByAccount(AccountId("acc-1"))
    } yield {
      found.map(_.balanceCents) shouldBe Some(777)
      found.map(_.balanceSource) shouldBe Some(BalanceSource.Bank)
      found.flatMap(_.balanceUpdatedAt) shouldBe Some(at)
      snaps.map(_.amount) shouldBe List(777)
    }
  }

  "setBalanceSource changes only the provenance" in {
    val repo    = new AccountRepositoryImpl(xa)
    val account = spendingAccount("acc-1", "Main", Currency.PLN, 5000)

    for {
      _     <- repo.create(account)
      _     <- repo.setBalanceSource(AccountId("acc-1"), BalanceSource.Bank)
      found <- repo.findById(AccountId("acc-1"))
    } yield {
      found.map(_.balanceSource) shouldBe Some(BalanceSource.Bank)
      found.map(_.balanceCents) shouldBe Some(5000)
    }
  }

  "delete removes account" in {
    val repo    = new AccountRepositoryImpl(xa)
    val account = spendingAccount("acc-1", "Test", Currency.PLN)

    for {
      _     <- repo.create(account)
      _     <- repo.delete(AccountId("acc-1"))
      found <- repo.findById(AccountId("acc-1"))
    } yield found shouldBe None
  }

  "existsWithCurrency reflects any account role" in {
    val repo = new AccountRepositoryImpl(xa)
    for {
      _      <- repo.create(savingsAccount("acc-1", "Fund", Currency.EUR, 0, None))
      hasEur <- repo.existsWithCurrency(Currency.EUR)
      hasUsd <- repo.existsWithCurrency(Currency.USD)
    } yield {
      hasEur shouldBe true
      hasUsd shouldBe false
    }
  }
}
