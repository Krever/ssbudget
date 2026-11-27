package ssbudget.frontend.util

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.shared.model.{Currency, Money}

/** How the page writes amounts. The number formats (`f"...%,.2f"`) are made by Scala.js here, not by the JVM, so this is the one place that checks
  * them in the form the browser runs.
  */
class FormattingSpec extends AnyFreeSpec with Matchers {

  "an amount has two decimals and groups of thousands" in {
    MoneyFormatter.formatBare(123456789L) shouldBe "1,234,567.89"
    MoneyFormatter.formatBare(-5L) shouldBe "-0.05"
    MoneyFormatter.formatBare(0L) shouldBe "0.00"
  }

  "a signed amount shows the plus sign, and zero has no sign" in {
    MoneyFormatter.formatSigned(1250L) shouldBe "+12.50"
    MoneyFormatter.formatSigned(-1250L) shouldBe "-12.50"
    MoneyFormatter.formatSigned(0L) shouldBe "0.00"
  }

  "the short form has no decimals" in {
    Formatting.formatMoneyShort(123456L) shouldBe "1,235"
    Formatting.formatMoneyShort(-99L) shouldBe "-1"
  }

  "a foreign amount is converted with the rate; with no rate the amount stays" in {
    val pln = Currency.PLN
    MoneyFormatter.convertToPrimary(Money(1000L, Currency.EUR), pln, Map(Currency.EUR -> 4.25)) shouldBe Money(4250L, pln)
    MoneyFormatter.convertToPrimary(Money(1000L, Currency.EUR), pln, Map.empty) shouldBe Money(1000L, pln)
    MoneyFormatter.convertToPrimary(Money(1000L, pln), pln, Map.empty) shouldBe Money(1000L, pln)
  }

  "the width of a progress bar is a whole percent" in {
    Formatting.progressWidth(0.456) shouldBe "width: 45%"
  }
}
