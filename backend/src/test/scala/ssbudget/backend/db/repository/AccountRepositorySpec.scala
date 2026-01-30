package ssbudget.backend.db.repository

import cats.effect.IO
import ssbudget.shared.model.*

class AccountRepositorySpec extends RepositorySpec {

  "create and findById returns the account" in {
    val repo    = new AccountRepositoryImpl(xa)
    val account = Account(AccountId("acc-1"), "Main Account", Currency.PLN)

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
    val acc1 = Account(AccountId("acc-1"), "Zebra", Currency.PLN)
    val acc2 = Account(AccountId("acc-2"), "Alpha", Currency.EUR)
    val acc3 = Account(AccountId("acc-3"), "Beta", Currency.PLN)

    for {
      _   <- repo.create(acc1)
      _   <- repo.create(acc2)
      _   <- repo.create(acc3)
      all <- repo.findAll
    } yield all shouldBe List(acc2, acc3, acc1)
  }

  "update modifies account" in {
    val repo    = new AccountRepositoryImpl(xa)
    val account = Account(AccountId("acc-1"), "Old Name", Currency.PLN)
    val updated = account.copy(name = "New Name", currency = Currency.EUR)

    for {
      _     <- repo.create(account)
      _     <- repo.update(updated)
      found <- repo.findById(AccountId("acc-1"))
    } yield found shouldBe Some(updated)
  }

  "delete removes account" in {
    val repo    = new AccountRepositoryImpl(xa)
    val account = Account(AccountId("acc-1"), "Test", Currency.PLN)

    for {
      _     <- repo.create(account)
      _     <- repo.delete(AccountId("acc-1"))
      found <- repo.findById(AccountId("acc-1"))
    } yield found shouldBe None
  }
}
