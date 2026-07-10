package ssbudget.backend.banking

import ssbudget.backend.db.Repositories
import ssbudget.backend.db.repository.RepositorySpec
import ssbudget.shared.model.*

import java.time.Instant

class RuleEngineServiceSpec extends RepositorySpec {

  private val at = Instant.parse("2026-01-10T00:00:00Z")

  import RuleCriterion.*
  import TextMatchOp.*

  private def tx(
      id: String,
      name: String = "Biedronka",
      amountCents: Long = -1000,
      internal: Boolean = false,
      categoryId: Option[CategoryId] = None,
      categorySource: Option[CategorySource] = None,
  ): BankTransaction =
    BankTransaction(
      BankTransactionId(id),
      BankConnectionId("c-1"),
      "uid-1",
      Some(id),
      id,
      amountCents,
      Currency.PLN,
      TransactionStatus.Booked,
      at,
      Some(name),
      None,
      Some("note"),
      None,
      categoryId,
      "{}",
      at,
      internal,
      categorySource,
    )

  private def rule(id: String, priority: Int, criteria: List[RuleCriterion], cat: String): ClassificationRule =
    ClassificationRule(ClassificationRuleId(id), s"rule-$id", CategoryId(cat), priority, criteria, at)

  private def fixtures = {
    val repos = Repositories.fromTransactor(xa)
    for {
      _ <- repos.categories.create(Category(CategoryId("groceries"), "Groceries", None))
      _ <- repos.categories.create(Category(CategoryId("fuel"), "Fuel", None))
    } yield (repos, new RuleEngineService(repos))
  }

  "assigns a matching rule's category with source Rule" in {
    for {
      (repos, engine) <- fixtures
      _               <- repos.bankTransactions.insertNew(tx("t1", name = "Biedronka 5"))
      _               <- repos.classificationRules.create(rule("r", 0, List(CounterpartyName(Contains, "biedronka")), "groceries"))
      n               <- engine.applyRules()
      after           <- repos.bankTransactions.findById(BankTransactionId("t1"))
    } yield {
      n shouldBe 1
      after.flatMap(_.categoryId) shouldBe Some(CategoryId("groceries"))
      after.flatMap(_.categorySource) shouldBe Some(CategorySource.Rule)
    }
  }

  "never clobbers a manually-set category" in {
    for {
      (repos, engine) <- fixtures
      _               <- repos.bankTransactions.insertNew(
                           tx("t1", name = "Biedronka", categoryId = Some(CategoryId("fuel")), categorySource = Some(CategorySource.Manual)),
                         )
      _               <- repos.classificationRules.create(rule("r", 0, List(CounterpartyName(Contains, "biedronka")), "groceries"))
      _               <- engine.applyRules()
      after           <- repos.bankTransactions.findById(BankTransactionId("t1"))
    } yield {
      after.flatMap(_.categoryId) shouldBe Some(CategoryId("fuel"))
      after.flatMap(_.categorySource) shouldBe Some(CategorySource.Manual)
    }
  }

  "full re-evaluation clears a rule-assigned category once the rule no longer matches" in {
    for {
      (repos, engine) <- fixtures
      _               <- repos.bankTransactions.insertNew(tx("t1", name = "Biedronka"))
      _               <- repos.classificationRules.create(rule("r", 0, List(CounterpartyName(Contains, "biedronka")), "groceries"))
      _               <- engine.applyRules()
      assigned        <- repos.bankTransactions.findById(BankTransactionId("t1"))
      _               <- repos.classificationRules.update(ClassificationRuleId("r"), "rule-r", CategoryId("groceries"), List(CounterpartyName(Contains, "orlen")))
      _               <- engine.applyRules()
      cleared         <- repos.bankTransactions.findById(BankTransactionId("t1"))
    } yield {
      assigned.flatMap(_.categoryId) shouldBe Some(CategoryId("groceries"))
      cleared.flatMap(_.categoryId) shouldBe None
      cleared.flatMap(_.categorySource) shouldBe None
    }
  }

  "first matching rule by priority wins" in {
    for {
      (repos, engine) <- fixtures
      _               <- repos.bankTransactions.insertNew(tx("t1", name = "Biedronka", amountCents = -1000))
      _               <- repos.classificationRules.create(rule("specific", 0, List(CounterpartyName(Contains, "biedronka")), "groceries"))
      _               <- repos.classificationRules.create(rule("catchall", 1, List(Direction(outflow = true)), "fuel"))
      _               <- engine.applyRules()
      after           <- repos.bankTransactions.findById(BankTransactionId("t1"))
    } yield after.flatMap(_.categoryId) shouldBe Some(CategoryId("groceries"))
  }

  "internal transfers are never categorized" in {
    for {
      (repos, engine) <- fixtures
      _               <- repos.bankTransactions.insertNew(tx("t1", name = "Biedronka", internal = true))
      _               <- repos.classificationRules.create(rule("r", 0, List(CounterpartyName(Contains, "biedronka")), "groceries"))
      _               <- engine.applyRules()
      after           <- repos.bankTransactions.findById(BankTransactionId("t1"))
    } yield after.flatMap(_.categoryId) shouldBe None
  }

  "is idempotent — a second run changes nothing" in {
    for {
      (repos, engine) <- fixtures
      _               <- repos.bankTransactions.insertNew(tx("t1", name = "Biedronka"))
      _               <- repos.classificationRules.create(rule("r", 0, List(CounterpartyName(Contains, "biedronka")), "groceries"))
      first           <- engine.applyRules()
      second          <- engine.applyRules()
    } yield {
      first shouldBe 1
      second shouldBe 0
    }
  }
}
