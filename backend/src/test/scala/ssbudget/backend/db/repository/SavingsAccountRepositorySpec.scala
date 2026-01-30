package ssbudget.backend.db.repository

import ssbudget.shared.model.*

class SavingsAccountRepositorySpec extends RepositorySpec {

  "create and findById returns the savings account" in {
    val repo    = new SavingsAccountRepositoryImpl(xa)
    val account = SavingsAccount(SavingsAccountId("sav-1"), "Emergency Fund", Currency.PLN, 100000, Some(50000))

    for {
      _     <- repo.create(account)
      found <- repo.findById(SavingsAccountId("sav-1"))
    } yield found shouldBe Some(account)
  }

  "findById returns None for non-existent account" in {
    val repo = new SavingsAccountRepositoryImpl(xa)

    for {
      found <- repo.findById(SavingsAccountId("non-existent"))
    } yield found shouldBe None
  }

  "findAll returns all savings accounts ordered by name" in {
    val repo = new SavingsAccountRepositoryImpl(xa)
    val acc1 = SavingsAccount(SavingsAccountId("sav-1"), "Zebra Fund", Currency.PLN, 0, None)
    val acc2 = SavingsAccount(SavingsAccountId("sav-2"), "Alpha Fund", Currency.EUR, 50000, Some(10000))
    val acc3 = SavingsAccount(SavingsAccountId("sav-3"), "Beta Fund", Currency.PLN, 25000, None)

    for {
      _   <- repo.create(acc1)
      _   <- repo.create(acc2)
      _   <- repo.create(acc3)
      all <- repo.findAll
    } yield all shouldBe List(acc2, acc3, acc1)
  }

  "update modifies savings account" in {
    val repo    = new SavingsAccountRepositoryImpl(xa)
    val account = SavingsAccount(SavingsAccountId("sav-1"), "Old Name", Currency.PLN, 100000, None)
    val updated = account.copy(name = "New Name", currentBalance = 150000, plannedMonthly = Some(25000))

    for {
      _     <- repo.create(account)
      _     <- repo.update(updated)
      found <- repo.findById(SavingsAccountId("sav-1"))
    } yield found shouldBe Some(updated)
  }

  "updateBalance modifies only the balance" in {
    val repo    = new SavingsAccountRepositoryImpl(xa)
    val account = SavingsAccount(SavingsAccountId("sav-1"), "Fund", Currency.PLN, 100000, Some(50000))

    for {
      _     <- repo.create(account)
      _     <- repo.updateBalance(SavingsAccountId("sav-1"), 200000)
      found <- repo.findById(SavingsAccountId("sav-1"))
    } yield {
      found.map(_.currentBalance) shouldBe Some(200000)
      found.map(_.name) shouldBe Some("Fund")
      found.flatMap(_.plannedMonthly) shouldBe Some(50000)
    }
  }

  "delete removes savings account" in {
    val repo    = new SavingsAccountRepositoryImpl(xa)
    val account = SavingsAccount(SavingsAccountId("sav-1"), "Test", Currency.PLN, 0, None)

    for {
      _     <- repo.create(account)
      _     <- repo.delete(SavingsAccountId("sav-1"))
      found <- repo.findById(SavingsAccountId("sav-1"))
    } yield found shouldBe None
  }

  "handles accounts without planned monthly target" in {
    val repo    = new SavingsAccountRepositoryImpl(xa)
    val account = SavingsAccount(SavingsAccountId("sav-1"), "No Target", Currency.EUR, 50000, None)

    for {
      _     <- repo.create(account)
      found <- repo.findById(SavingsAccountId("sav-1"))
    } yield {
      found shouldBe Some(account)
      found.flatMap(_.plannedMonthly) shouldBe None
    }
  }
}
