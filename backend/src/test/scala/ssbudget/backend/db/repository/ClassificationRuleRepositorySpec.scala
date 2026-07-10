package ssbudget.backend.db.repository

import ssbudget.shared.model.*

import java.time.Instant

class ClassificationRuleRepositorySpec extends RepositorySpec {

  private val at = Instant.parse("2026-01-10T00:00:00Z")

  import RuleCriterion.*
  import TextMatchOp.*

  private def rule(id: String, priority: Int, criteria: List[RuleCriterion], cat: String = "cat-1"): ClassificationRule =
    ClassificationRule(ClassificationRuleId(id), s"rule-$id", CategoryId(cat), priority, criteria, at)

  private def withCategories = {
    val catRepo = new CategoryRepositoryImpl(xa)
    for {
      _ <- catRepo.create(Category(CategoryId("cat-1"), "Groceries", None))
      _ <- catRepo.create(Category(CategoryId("cat-2"), "Fuel", None))
    } yield ()
  }

  "create and findAll ordered by priority" in {
    val repo = new ClassificationRuleRepositoryImpl(xa)
    for {
      _   <- withCategories
      _   <- repo.create(rule("b", 1, List(Direction(true))))
      _   <- repo.create(rule("a", 0, List(Direction(false))))
      all <- repo.findAll
    } yield all.map(_.id.value) shouldBe List("a", "b")
  }

  "criteria JSON round-trips for every variant" in {
    val repo     = new ClassificationRuleRepositoryImpl(xa)
    val criteria = List[RuleCriterion](
      CounterpartyName(Contains, "Biedronka"),
      Remittance(Equals, "note"),
      CounterpartyAccount("PL10"),
      BankTransactionCode("PMNT"),
      Account("uid-1"),
      Direction(outflow = true),
      AmountCompare(AmountMatchOp.Gt, 1234),
      AmountBetween(100, 900),
      CurrencyIs(Currency.EUR),
    )
    for {
      _     <- withCategories
      _     <- repo.create(rule("r", 0, criteria))
      found <- repo.findById(ClassificationRuleId("r"))
    } yield found.map(_.criteria) shouldBe Some(criteria)
  }

  "legacy amountEquals JSON decodes as AmountCompare(Eq)" in {
    import io.circe.parser.decode
    decode[RuleCriterion]("""{"kind":"amountEquals","absCents":1234}""") shouldBe Right(AmountCompare(AmountMatchOp.Eq, 1234))
  }

  "update changes name, category and criteria" in {
    val repo = new ClassificationRuleRepositoryImpl(xa)
    for {
      _       <- withCategories
      _       <- repo.create(rule("r", 0, List(Direction(true))))
      _       <- repo.update(ClassificationRuleId("r"), "renamed", CategoryId("cat-2"), List(CurrencyIs(Currency.EUR)))
      updated <- repo.findById(ClassificationRuleId("r"))
    } yield {
      updated.map(_.name) shouldBe Some("renamed")
      updated.map(_.categoryId) shouldBe Some(CategoryId("cat-2"))
      updated.map(_.criteria) shouldBe Some(List(CurrencyIs(Currency.EUR)))
    }
  }

  "reorder rewrites priorities to 0..n-1 in the given order" in {
    val repo = new ClassificationRuleRepositoryImpl(xa)
    for {
      _   <- withCategories
      _   <- repo.create(rule("a", 0, List(Direction(true))))
      _   <- repo.create(rule("b", 1, List(Direction(false))))
      _   <- repo.create(rule("c", 2, List(CurrencyIs(Currency.PLN))))
      _   <- repo.reorder(List(ClassificationRuleId("c"), ClassificationRuleId("a"), ClassificationRuleId("b")))
      all <- repo.findAll
    } yield {
      all.map(_.id.value) shouldBe List("c", "a", "b")
      all.map(_.priority) shouldBe List(0, 1, 2)
    }
  }

  "nextPriority is max+1, or 0 when empty" in {
    val repo = new ClassificationRuleRepositoryImpl(xa)
    for {
      _     <- withCategories
      empty <- repo.nextPriority
      _     <- repo.create(rule("a", 0, List(Direction(true))))
      _     <- repo.create(rule("b", 5, List(Direction(false))))
      next  <- repo.nextPriority
    } yield {
      empty shouldBe 0
      next shouldBe 6
    }
  }

  "delete and deleteByCategory" in {
    val repo = new ClassificationRuleRepositoryImpl(xa)
    for {
      _        <- withCategories
      _        <- repo.create(rule("a", 0, List(Direction(true)), cat = "cat-1"))
      _        <- repo.create(rule("b", 1, List(Direction(false)), cat = "cat-2"))
      _        <- repo.delete(ClassificationRuleId("a"))
      afterDel <- repo.findAll
      _        <- repo.deleteByCategory(CategoryId("cat-2"))
      afterCat <- repo.findAll
    } yield {
      afterDel.map(_.id.value) shouldBe List("b")
      afterCat shouldBe Nil
    }
  }

  "deleteAll removes every rule" in {
    val repo = new ClassificationRuleRepositoryImpl(xa)
    for {
      _     <- withCategories
      _     <- repo.create(rule("a", 0, List(Direction(true))))
      _     <- repo.create(rule("b", 1, List(Direction(false))))
      _     <- repo.deleteAll
      after <- repo.findAll
    } yield after shouldBe Nil
  }
}
