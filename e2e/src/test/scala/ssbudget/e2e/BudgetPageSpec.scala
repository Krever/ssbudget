package ssbudget.e2e

import org.openqa.selenium.By
import scala.jdk.CollectionConverters.*

class BudgetPageSpec extends E2ESpec {

  /** The planned-item row for `name`, re-found on each call: Laminar replaces rows on every update, so a captured reference goes stale. */
  private def plannedRow(name: String): org.openqa.selenium.WebElement =
    findCard("Planned Items").findElement(By.xpath(s".//tr[.//td[contains(text(),'$name')]]"))

  /** Turn off "Pending only". Settled items are hidden by it, and asserting against the whole card instead would match the "Paid" column HEADER — a
    * false positive that makes a payment test pass without a payment.
    */
  private def showAllItems(): Unit = {
    val toggle = driver.findElement(By.id("showPendingOnly"))
    if toggle.isSelected then toggle.click()
  }

  /** Open `name`'s payment editor and submit it with `submit` ("Pay"/"Receive" to settle, "Part" for an instalment). `amount = None` keeps the
    * pre-filled remainder.
    */
  private def payPlanned(name: String, submit: String, amount: Option[String] = None): Unit = {
    click(plannedRow(name), "Pay")
    val payRow = findCard("Planned Items").findElement(By.cssSelector("tr.table-info"))
    amount.foreach { a =>
      val input = payRow.findElement(By.cssSelector("input[type='number']"))
      input.clear()
      input.sendKeys(a)
    }
    click(payRow, submit)
  }

  "Budget page" should "load the planned items and category budgets cards" in {
    ensurePeriodExists()

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val cardTexts = driver.findElements(By.cssSelector(".card")).asScala.map(_.getText).toList
    cardTexts.exists(_.contains("Planned Items")) shouldBe true
    cardTexts.exists(_.contains("Category Budgets")) shouldBe true
  }

  it should "add a new planned expense" in {
    ensurePeriodExists()

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card = findCard("Planned Items")
    click(card, "+ Expense")

    val addRow = card.findElement(By.cssSelector("tr.table-primary"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys("Test Expense")
    addRow.findElement(By.cssSelector("input[type='number']")).sendKeys("123.45")
    click(addRow, "Add")

    rowShouldExist(card, "Test Expense")
  }

  it should "add a new planned income" in {
    ensurePeriodExists()

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card = findCard("Planned Items")
    click(card, "+ Income")

    val addRow = card.findElement(By.cssSelector("tr.table-primary"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys("Test Income")
    addRow.findElement(By.cssSelector("input[type='number']")).sendKeys("500")
    click(addRow, "Add")

    rowShouldExist(card, "Test Income")
  }

  it should "pay expense with default amount" in {
    ensurePeriodExists()
    addPlannedExpense("Pay Test Expense", 100.00)

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    payPlanned("Pay Test Expense", "Pay")

    showAllItems()
    textShouldAppear(plannedRow("Pay Test Expense"), "Paid")
    textShouldAppear(plannedRow("Pay Test Expense"), "100.00")
  }

  it should "pay expense with overridden amount" in {
    ensurePeriodExists()
    addPlannedExpense("Override Pay Expense", 100.00)

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    payPlanned("Override Pay Expense", "Pay", Some("99.99"))

    // Settling under the estimate closes the item at the actual amount rather than leaving a 0.01 residual.
    showAllItems()
    textShouldAppear(plannedRow("Override Pay Expense"), "99.99")
    textShouldAppear(plannedRow("Override Pay Expense"), "Paid")
  }

  it should "part-pay an expense, leaving the remainder outstanding" in {
    ensurePeriodExists()
    addPlannedExpense("Part Pay Expense", 200.00)

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    payPlanned("Part Pay Expense", "Part", Some("50"))

    // Still open, with 50 paid and 150 of the 200 estimate still expected — a part-paid item stays visible under "Pending only".
    textShouldAppear(plannedRow("Part Pay Expense"), "Partial")
    textShouldAppear(plannedRow("Part Pay Expense"), "50.00")
    textShouldAppear(plannedRow("Part Pay Expense"), "150.00")
  }

  it should "settle the remainder of a part-paid expense" in {
    ensurePeriodExists()
    addPlannedExpense("Settle Rest Expense", 200.00)

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    // First instalment: 50 of 200.
    payPlanned("Settle Rest Expense", "Part", Some("50"))
    textShouldAppear(plannedRow("Settle Rest Expense"), "Partial")

    // The editor pre-fills the REMAINDER (150), and paying it accumulates to the full 200 rather than overwriting the instalment.
    payPlanned("Settle Rest Expense", "Pay")

    showAllItems()
    textShouldAppear(plannedRow("Settle Rest Expense"), "Paid")
    textShouldAppear(plannedRow("Settle Rest Expense"), "200.00")
  }

  it should "reset a part-payment back to pending" in {
    ensurePeriodExists()
    addPlannedExpense("Reset Part Expense", 100.00)

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    payPlanned("Reset Part Expense", "Part", Some("30"))
    textShouldAppear(plannedRow("Reset Part Expense"), "Partial")

    click(plannedRow("Reset Part Expense"), "Reset")
    textShouldAppear(plannedRow("Reset Part Expense"), "Pending")
  }
}
