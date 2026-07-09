package ssbudget.backend.db.repository

import ssbudget.shared.model.*

class CardGroupRepositorySpec extends RepositorySpec {

  private def group(id: String, name: String, limit: Long, currency: Currency, accountId: Option[AccountId]): CardGroup =
    CardGroup(CardGroupId(id), name, limit, currency, accountId)

  "create and findById returns the group (unlinked)" in {
    val repo = new CardGroupRepositoryImpl(xa)
    val g    = group("g-1", "PKO cards", 1000000, Currency.PLN, None)
    for {
      _     <- repo.create(g)
      found <- repo.findById(CardGroupId("g-1"))
    } yield found shouldBe Some(g)
  }

  "create and findById returns the group linked to an account" in {
    val accountRepo = new AccountRepositoryImpl(xa)
    val repo        = new CardGroupRepositoryImpl(xa)
    val g           = group("g-1", "PKO cards", 1000000, Currency.PLN, Some(AccountId("acc-1")))
    for {
      _     <- accountRepo.create(spendingAccount("acc-1", "Mirror", Currency.PLN))
      _     <- repo.create(g)
      found <- repo.findById(CardGroupId("g-1"))
    } yield found shouldBe Some(g)
  }

  "findAll returns groups ordered by name" in {
    val repo = new CardGroupRepositoryImpl(xa)
    val g1   = group("g-1", "Zebra", 100, Currency.PLN, None)
    val g2   = group("g-2", "Alpha", 200, Currency.EUR, None)
    for {
      _   <- repo.create(g1)
      _   <- repo.create(g2)
      all <- repo.findAll
    } yield all shouldBe List(g2, g1)
  }

  "findByAccount returns the group mirroring a given account, or None" in {
    val accountRepo = new AccountRepositoryImpl(xa)
    val repo        = new CardGroupRepositoryImpl(xa)
    for {
      _        <- accountRepo.create(spendingAccount("acc-1", "Mirror", Currency.PLN))
      _        <- repo.create(group("g-1", "Linked", 100, Currency.PLN, Some(AccountId("acc-1"))))
      _        <- repo.create(group("g-2", "Unlinked", 100, Currency.PLN, None))
      linked   <- repo.findByAccount(AccountId("acc-1"))
      unlinked <- repo.findByAccount(AccountId("acc-none"))
    } yield {
      linked.map(_.id) shouldBe Some(CardGroupId("g-1"))
      unlinked shouldBe None
    }
  }

  "setAccount links then unlinks the mirror account" in {
    val accountRepo = new AccountRepositoryImpl(xa)
    val repo        = new CardGroupRepositoryImpl(xa)
    for {
      _       <- accountRepo.create(spendingAccount("acc-1", "Mirror", Currency.PLN))
      _       <- repo.create(group("g-1", "Group", 100, Currency.PLN, None))
      _       <- repo.setAccount(CardGroupId("g-1"), Some(AccountId("acc-1")))
      linked  <- repo.findById(CardGroupId("g-1"))
      _       <- repo.setAccount(CardGroupId("g-1"), None)
      cleared <- repo.findById(CardGroupId("g-1"))
    } yield {
      linked.flatMap(_.accountId) shouldBe Some(AccountId("acc-1"))
      cleared.flatMap(_.accountId) shouldBe None
    }
  }

  "delete removes the group" in {
    val repo = new CardGroupRepositoryImpl(xa)
    for {
      _     <- repo.create(group("g-1", "Group", 100, Currency.PLN, None))
      _     <- repo.delete(CardGroupId("g-1"))
      found <- repo.findById(CardGroupId("g-1"))
    } yield found shouldBe None
  }
}
