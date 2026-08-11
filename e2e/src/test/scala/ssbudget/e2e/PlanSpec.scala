package ssbudget.e2e

import org.openqa.selenium.By
import ssbudget.shared.model.CategoryBudgetType

import scala.jdk.CollectionConverters.*

class PlanSpec extends E2ESpec {

  /** Open `name`'s payment editor and submit it with `submit` ("Pay"/"Receive" to settle, "Part" for an instalment). `amount = None` keeps the
    * pre-filled remainder.
    */
  private def payPlanned(name: String, submit: String, amount: Option[String] = None): Unit = {
    click(planEntry(name), "Pay")
    // The editor opens inside the entry, replacing its state line.
    val entry = planEntry(name)
    amount.foreach { a =>
      val input = entry.findElement(By.cssSelector("input[type='number']"))
      input.clear()
      input.sendKeys(a)
    }
    click(entry, submit)
  }

  "The Dashboard" should "load the plan" in {
    ensurePeriodExists()
    // A group's heading only exists once it has an entry, so give the expenses group one.
    addPlannedExpense("Plan Load Expense", 42)

    openDashboard()

    val cardTexts = driver.findElements(By.cssSelector(".card")).asScala.map(_.getText).toList
    cardTexts.exists(_.contains("Plan")) shouldBe true
    // Both kinds of entry share the one list, grouped by direction.
    cardTexts.exists(_.contains("EXPENSES")) shouldBe true
    cardTexts.exists(_.contains("Still to pay")) shouldBe true
  }

  it should "add a new planned expense" in {
    ensurePeriodExists()

    openDashboard()

    val card = findCard("Plan")
    click(card, "+ Expense")

    val form = driver.findElement(By.id("plan-add-expense"))
    form.findElement(By.cssSelector("input[type='text']")).sendKeys("Test Expense")
    form.findElement(By.cssSelector("input[type='number']")).sendKeys("123.45")
    click(form, "Add")

    textShouldAppear(findCard("Plan"), "Test Expense")
  }

  it should "add a new planned income" in {
    ensurePeriodExists()

    openDashboard()

    val card = findCard("Plan")
    click(card, "+ Income")

    val form = driver.findElement(By.id("plan-add-income"))
    form.findElement(By.cssSelector("input[type='text']")).sendKeys("Test Income")
    form.findElement(By.cssSelector("input[type='number']")).sendKeys("500")
    click(form, "Add")

    textShouldAppear(findCard("Plan"), "Test Income")
  }

  it should "pay expense with default amount" in {
    ensurePeriodExists()
    addPlannedExpense("Pay Test Expense", 100.00)

    openDashboard()

    payPlanned("Pay Test Expense", "Pay")

    showDonePlanEntries()
    textShouldAppear(planEntry("Pay Test Expense"), "paid ✓")
    textShouldAppear(planEntry("Pay Test Expense"), "100.00")
  }

  it should "pay expense with overridden amount" in {
    ensurePeriodExists()
    addPlannedExpense("Override Pay Expense", 100.00)

    openDashboard()

    payPlanned("Override Pay Expense", "Pay", Some("99.99"))

    // Settling under the estimate closes the item at the actual amount rather than leaving a 0.01 residual.
    showDonePlanEntries()
    textShouldAppear(planEntry("Override Pay Expense"), "99.99")
    textShouldAppear(planEntry("Override Pay Expense"), "paid ✓")
  }

  it should "part-pay an expense, leaving the remainder outstanding" in {
    ensurePeriodExists()
    addPlannedExpense("Part Pay Expense", 200.00)

    openDashboard()

    payPlanned("Part Pay Expense", "Part", Some("50"))

    // Still open, with 50 paid and 150 of the 200 estimate still expected — a part-paid item stays visible under "Pending only".
    textShouldAppear(planEntry("Part Pay Expense"), "paid 50.00 of 200.00")
    textShouldAppear(planEntry("Part Pay Expense"), "reserved 150.00")
  }

  it should "settle the remainder of a part-paid expense" in {
    ensurePeriodExists()
    addPlannedExpense("Settle Rest Expense", 200.00)

    openDashboard()

    // First instalment: 50 of 200.
    payPlanned("Settle Rest Expense", "Part", Some("50"))
    textShouldAppear(planEntry("Settle Rest Expense"), "paid 50.00 of 200.00")

    // The editor pre-fills the REMAINDER (150), and paying it accumulates to the full 200 rather than overwriting the instalment.
    payPlanned("Settle Rest Expense", "Pay")

    showDonePlanEntries()
    textShouldAppear(planEntry("Settle Rest Expense"), "paid ✓")
    textShouldAppear(planEntry("Settle Rest Expense"), "200.00 / 200.00")
  }

  it should "reset a part-payment back to pending" in {
    ensurePeriodExists()
    addPlannedExpense("Reset Part Expense", 100.00)

    openDashboard()

    payPlanned("Reset Part Expense", "Part", Some("30"))
    textShouldAppear(planEntry("Reset Part Expense"), "paid 30.00 of 100.00")

    click(planEntry("Reset Part Expense"), "Reset")
    textShouldAppear(planEntry("Reset Part Expense"), "not paid yet")
  }

  it should "keep the bar to the plan while nothing has overrun it" in {
    ensurePeriodExists()
    val category = TransactionSeed.addCategory("Bar Within Plan", Some(CategoryBudgetType.Steady))
    TransactionSeed.addTransaction(
      "Within last month",
      -30000,
      bookedAt = TransactionSeed.lastMonth,
      categoryId = Some(category),
    ) // plan: 300.00, nothing spent yet

    openDashboard()

    val entry = planEntry("Bar Within Plan")
    // The plan is the whole bar: nothing steps past it, so nothing is marked as over it.
    val bar   = entry.findElement(By.cssSelector(".progress"))
    bar.getAttribute("title") should include("plan 300.00")
    bar.getAttribute("title") should not include "over plan by"
    entry.findElement(By.cssSelector(".progress-bar")).getAttribute("title") should include("reserved 300.00")
  }

  it should "step past the plan rather than saturating" in {
    ensurePeriodExists()
    val category = TransactionSeed.addCategory("Bar Anatomy", Some(CategoryBudgetType.Steady))
    TransactionSeed.addTransaction("Anatomy last month", -30000, bookedAt = TransactionSeed.lastMonth, categoryId = Some(category)) // plan: 300.00
    TransactionSeed.addTransaction("Anatomy overspend", -52000, categoryId = Some(category))                                        // spent: 520.00

    openDashboard()

    val entry = planEntry("Bar Anatomy")
    entry.getText should include("over budget")

    // The bar states what the plan was and what the period is now forecast to cost.
    val title = entry.findElement(By.cssSelector(".progress")).getAttribute("title")
    title should include("plan 300.00")
    title should include("forecast 820.00")
    title should include("over plan by 520.00")

    // Spend is drawn either side of the plan's shoulder; what's still reserved sits beyond it. The parts past the plan step down to a slimmer band,
    // which is what keeps the shoulder visible instead of saturating the bar.
    val segments = entry.findElements(By.cssSelector(".progress-bar")).asScala.toList
    segments.count(_.getAttribute("title").contains("spent 520.00")) shouldBe 2
    val reserved = segments.filter(_.getAttribute("title").contains("reserved 300.00"))
    reserved.size shouldBe 1
    reserved.head.getAttribute("class") should include("progress-bar-striped")
    segments.count(_.getAttribute("style").contains("border-top")) shouldBe 2

    // And the pace marker is still there, inside the plan it belongs to.
    entry.findElements(By.xpath(".//div[@title='expected by now']")).size shouldBe 1
  }

  it should "show a bill as done or not, with no bar to fill" in {
    ensurePeriodExists()
    val category = TransactionSeed.addCategory("Bar Bill", Some(CategoryBudgetType.Bill))
    TransactionSeed.addTransaction("Bill last month", -50000, bookedAt = TransactionSeed.lastMonth, categoryId = Some(category))

    openDashboard()

    // A bill either happened this period or it didn't, so it gets a marker instead of a proportion.
    val entry = planEntry("Bar Bill")
    entry.getText should include("○")
    entry.getText should include("not paid yet")
    assertAbsent(entry, By.cssSelector(".progress"))

    // Once it's covered the marker flips, still without a bar.
    TransactionSeed.addTransaction("Bill this period", -50000, categoryId = Some(category))
    openDashboard()
    showDonePlanEntries()

    eventually {
      val covered = planEntry("Bar Bill")
      covered.getText should include("✔")
      covered.getText should include("paid")
    }
    assertAbsent(planEntry("Bar Bill"), By.cssSelector(".progress"))
  }
}
