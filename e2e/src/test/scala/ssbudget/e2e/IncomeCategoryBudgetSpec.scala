package ssbudget.e2e

import org.openqa.selenium.{By, WebElement}
import ssbudget.shared.model.CategoryBudgetType

/** A category whose money flows IN (rent from a tenant, a recurring refund) used as a category budget. Its net spend is negative, so every budget
  * figure mirrors: the card predicts what's still to ARRIVE and the Dashboard's free-money breakdown counts it as income rather than reserving it.
  */
class IncomeCategoryBudgetSpec extends E2ESpec {

  "An income category budget" should "predict what is still expected to arrive" in {
    ensurePeriodExists()
    val categoryId = TransactionSeed.addCategory("E2E Tenant Rent", Some(CategoryBudgetType.Bill))
    TransactionSeed.addTransaction("Tenant E2E", 150000, bookedAt = TransactionSeed.lastMonth, categoryId = Some(categoryId))

    openDashboard()

    // Listed under Incomes, and its state is an awaited arrival rather than an unpaid bill. The amount is what's still expected to come in.
    eventually {
      val row = planEntry("E2E Tenant Rent")
      row.getText should include("not received yet")
      row.getText should include("1,500.00")
    }
  }

  /** A row of the free-money breakdown, in cents. The Dashboard shows one figure per direction rather than one per kind of entry, so this is how an
    * income budget's contribution is checked: by what it moves, not by a row of its own.
    */
  private def freeMoneyRow(label: String): Long = {
    val row  = findCard("Free money").findElement(By.xpath(s".//div[span[text()='$label']]"))
    val text = row.getText
    val last = "([0-9,]+\\.[0-9]{2})".r
      .findAllMatchIn(text)
      .map(_.group(1))
      .toList
      .lastOption
      .getOrElse(fail(s"no amount in free-money row '$label': $text"))
    Math.round(last.replace(",", "").toDouble * 100)
  }

  it should "add to free money instead of being reserved" in {
    ensurePeriodExists()
    openDashboard()
    val receivableBefore = freeMoneyRow("+ Still to receive")
    val payableBefore    = freeMoneyRow("- Still to pay")

    // Seed the income budget only now, so its effect on the breakdown is measurable against what was there before.
    val categoryId = TransactionSeed.addCategory("E2E Refund Stream", Some(CategoryBudgetType.Bill))
    TransactionSeed.addTransaction("Refunder E2E", 40000, bookedAt = TransactionSeed.lastMonth, categoryId = Some(categoryId))

    openDashboard()

    eventually {
      // The 400.00 it expects lands on the incoming side, and nothing gets reserved for it as if it were spend.
      freeMoneyRow("+ Still to receive") shouldBe receivableBefore + 40000
      freeMoneyRow("- Still to pay") shouldBe payableBefore
    }
  }

  it should "count as received once the money lands this period" in {
    ensurePeriodExists()
    val categoryId = TransactionSeed.addCategory("E2E Arrived Income", Some(CategoryBudgetType.Bill))
    TransactionSeed.addTransaction("Payer E2E", 90000, bookedAt = TransactionSeed.lastMonth, categoryId = Some(categoryId))
    TransactionSeed.addTransaction("Payer E2E now", 90000, categoryId = Some(categoryId))

    openDashboard()

    // Settled in the arriving direction, so "Hide done" drops it exactly like a paid bill.
    findCard("Plan").getText should not include "E2E Arrived Income"

    showDonePlanEntries()
    // A bill's state is a marker, not a bar: ✔ once the money is in.
    eventually {
      val entry = planEntry("E2E Arrived Income")
      entry.getText should include("✔")
      entry.getText should include("received")
    }
  }
}
