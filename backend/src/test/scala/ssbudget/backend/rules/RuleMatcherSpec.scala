package ssbudget.backend.rules

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.shared.model.*
import ssbudget.shared.rules.RuleMatcher

import java.time.Instant

class RuleMatcherSpec extends AnyFreeSpec with Matchers {

  private val at = Instant.parse("2026-01-10T00:00:00Z")

  private def tx(
      amountCents: Long = -1234,
      currency: Currency = Currency.PLN,
      counterpartyName: Option[String] = Some("Biedronka 123"),
      counterpartyAccount: Option[String] = Some("PL10 2040 0000"),
      remittance: Option[String] = Some("Zakupy spożywcze"),
      bankTransactionCode: Option[String] = Some("PMNT"),
      ebAccountUid: String = "uid-1",
      internal: Boolean = false,
  ): BankTransaction =
    BankTransaction(
      BankTransactionId("t"),
      BankConnectionId("c"),
      ebAccountUid,
      None,
      "d",
      amountCents,
      currency,
      TransactionStatus.Booked,
      at,
      counterpartyName,
      counterpartyAccount,
      remittance,
      bankTransactionCode,
      None,
      "{}",
      at,
      internal,
      None,
    )

  private def rule(id: String, priority: Int, criteria: List[RuleCriterion], cat: String = "cat"): ClassificationRule =
    ClassificationRule(ClassificationRuleId(id), id, CategoryId(cat), priority, criteria, at)

  import RuleCriterion.*
  import TextMatchOp.*

  "matchesOne" - {
    "counterparty name contains (case/space-insensitive)" in {
      RuleMatcher.matchesOne(CounterpartyName(Contains, "biedronka"), tx()) shouldBe true
      RuleMatcher.matchesOne(CounterpartyName(Equals, "biedronka 123"), tx()) shouldBe true
      RuleMatcher.matchesOne(CounterpartyName(Equals, "biedronka"), tx()) shouldBe false
    }
    "remittance contains" in {
      RuleMatcher.matchesOne(Remittance(Contains, "spożywcze"), tx()) shouldBe true
      RuleMatcher.matchesOne(Remittance(Contains, "paliwo"), tx()) shouldBe false
    }
    "counterparty account normalized IBAN equals" in {
      RuleMatcher.matchesOne(CounterpartyAccount("pl1020400000"), tx()) shouldBe true
      RuleMatcher.matchesOne(CounterpartyAccount("DE00"), tx()) shouldBe false
    }
    "bank transaction code equals" in {
      RuleMatcher.matchesOne(BankTransactionCode("PMNT"), tx()) shouldBe true
      RuleMatcher.matchesOne(BankTransactionCode("XXXX"), tx()) shouldBe false
    }
    "account uid equals" in {
      RuleMatcher.matchesOne(Account("uid-1"), tx()) shouldBe true
      RuleMatcher.matchesOne(Account("uid-2"), tx()) shouldBe false
    }
    "direction on sign of amount" in {
      RuleMatcher.matchesOne(Direction(outflow = true), tx(amountCents = -100)) shouldBe true
      RuleMatcher.matchesOne(Direction(outflow = true), tx(amountCents = 100)) shouldBe false
      RuleMatcher.matchesOne(Direction(outflow = false), tx(amountCents = 100)) shouldBe true
    }
    "amount compare and between operate on absolute value" in {
      RuleMatcher.matchesOne(AmountCompare(AmountMatchOp.Eq, 1234), tx(amountCents = -1234)) shouldBe true
      RuleMatcher.matchesOne(AmountCompare(AmountMatchOp.Eq, 1234), tx(amountCents = 1234)) shouldBe true
      RuleMatcher.matchesOne(AmountCompare(AmountMatchOp.Lt, 1234), tx(amountCents = -1000)) shouldBe true
      RuleMatcher.matchesOne(AmountCompare(AmountMatchOp.Lt, 1234), tx(amountCents = -1234)) shouldBe false
      RuleMatcher.matchesOne(AmountCompare(AmountMatchOp.Gt, 1234), tx(amountCents = -2000)) shouldBe true
      RuleMatcher.matchesOne(AmountCompare(AmountMatchOp.Gt, 1234), tx(amountCents = 1234)) shouldBe false
      RuleMatcher.matchesOne(AmountBetween(1000, 2000), tx(amountCents = -1234)) shouldBe true
      RuleMatcher.matchesOne(AmountBetween(1000, 1200), tx(amountCents = -1234)) shouldBe false
    }
    "currency equals" in {
      RuleMatcher.matchesOne(CurrencyIs(Currency.PLN), tx()) shouldBe true
      RuleMatcher.matchesOne(CurrencyIs(Currency.EUR), tx()) shouldBe false
    }
  }

  "matches AND-combines and requires non-empty" in {
    RuleMatcher.matches(List(CounterpartyName(Contains, "biedronka"), Direction(true)), tx()) shouldBe true
    RuleMatcher.matches(List(CounterpartyName(Contains, "biedronka"), Direction(false)), tx()) shouldBe false
    RuleMatcher.matches(Nil, tx()) shouldBe false
  }

  "firstMatch" - {
    "returns None for internal transfers" in {
      RuleMatcher.firstMatch(List(rule("r", 0, List(Direction(true)))), tx(internal = true)) shouldBe None
    }
    "respects ascending priority order" in {
      val rules = List(
        rule("groceries", 0, List(CounterpartyName(Contains, "biedronka"))),
        rule("catchall", 1, List(Direction(true))),
      )
      RuleMatcher.firstMatch(rules, tx()).map(_.id.value) shouldBe Some("groceries")
      RuleMatcher.firstMatch(rules, tx(counterpartyName = Some("Orlen"))).map(_.id.value) shouldBe Some("catchall")
    }
    "returns None when nothing matches" in {
      RuleMatcher.firstMatch(List(rule("r", 0, List(CounterpartyName(Contains, "zzz")))), tx()) shouldBe None
    }
  }

  "candidateCriteria produces the expected prefill in display order" in {
    val cands = RuleMatcher.candidateCriteria(tx())
    cands.head shouldBe CounterpartyName(Contains, "Biedronka 123")
    cands should contain(Remittance(Contains, "Zakupy spożywcze"))
    cands should contain(CounterpartyAccount("PL10 2040 0000"))
    cands should contain(BankTransactionCode("PMNT"))
    cands should contain(Account("uid-1"))
    cands should contain(Direction(outflow = true))
    cands should contain(AmountCompare(AmountMatchOp.Eq, 1234))
    cands should contain(CurrencyIs(Currency.PLN))
  }

  "candidateCriteria omits missing optional fields" in {
    val cands = RuleMatcher.candidateCriteria(tx(counterpartyName = None, counterpartyAccount = None, bankTransactionCode = None))
    cands.exists(_.isInstanceOf[CounterpartyName]) shouldBe false
    cands.exists(_.isInstanceOf[CounterpartyAccount]) shouldBe false
    cands.exists(_.isInstanceOf[BankTransactionCode]) shouldBe false
    cands should contain(Account("uid-1")) // always present
  }
}
