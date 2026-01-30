package ssbudget.e2e

import org.openqa.selenium.By
import scala.jdk.CollectionConverters.*

class BudgetPageSpec extends E2ESpec {

  "Budget page" should "load planned items and estimated expenses cards" in {
    ensurePeriodExists()

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val cardTexts = driver.findElements(By.cssSelector(".card")).asScala.map(_.getText).toList
    cardTexts.exists(_.contains("Planned Items")) shouldBe true
    cardTexts.exists(_.contains("Estimated Expenses")) shouldBe true
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

    rows(card).exists(_.getText.contains("Test Expense")) shouldBe true
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

    rows(card).exists(_.getText.contains("Test Income")) shouldBe true
  }

  it should "pay expense with default amount" in {
    ensurePeriodExists()
    addPlannedExpense("Pay Test Expense", 100.00)

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card       = findCard("Planned Items")
    val pendingRow = card.findElement(By.xpath(".//tr[.//td[contains(text(),'Pay Test Expense')]]"))

    click(pendingRow, "Pay")
    click(card.findElement(By.cssSelector("tr.table-info")), "Save")

    card.getText should include("Paid")
  }

  it should "pay expense with overridden amount" in {
    ensurePeriodExists()
    addPlannedExpense("Override Pay Expense", 100.00)

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card       = findCard("Planned Items")
    val pendingRow = card.findElement(By.xpath(".//tr[.//td[contains(text(),'Override Pay Expense')]]"))

    click(pendingRow, "Pay")
    val editRow = card.findElement(By.cssSelector("tr.table-info"))
    val input   = editRow.findElement(By.cssSelector("input[type='number']"))
    input.clear()
    input.sendKeys("99.99")
    click(editRow, "Save")

    card.getText should include("99.99")
  }

  it should "add and delete an estimated expense" in {
    ensurePeriodExists()

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card = findCard("Estimated Expenses")
    click(card, "+ Add")

    val addRow = card.findElement(By.cssSelector("tr.table-primary"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys("To Delete Expense")
    addRow.findElement(By.cssSelector("input[type='number']")).sendKeys("100")
    click(addRow, "Add")

    rows(card).exists(_.getText.contains("To Delete Expense")) shouldBe true

    val toDelete = card.findElement(By.xpath(".//tr[.//td[contains(text(),'To Delete Expense')]]"))
    click(toDelete, "Edit")
    click(card.findElement(By.cssSelector("tr.table-warning")), "Del")

    rows(card).exists(_.getText.contains("To Delete Expense")) shouldBe false
  }

  // ============ Planned Savings ============

  it should "show planned savings card" in {
    ensurePeriodExists()

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val cardTexts = driver.findElements(By.cssSelector(".card")).asScala.map(_.getText).toList
    cardTexts.exists(_.contains("Planned Savings")) shouldBe true
  }

  it should "show savings accounts with targets" in {
    ensurePeriodExists()
    addSavingsAccount("Budget Savings Test", Some(500))

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card = findCard("Planned Savings")
    card.getText should include("Budget Savings Test")
    card.getText should include("Target")
    card.getText should include("Saved")
    card.getText should include("Remaining")
  }

  it should "expand savings account to show transactions" in {
    ensurePeriodExists()
    addSavingsAccount("Expand Test Savings", Some(500))

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card       = findCard("Planned Savings")
    // Find a savings account row and click to expand
    val savingsRow = card.findElement(By.xpath(".//tr[.//td[contains(text(),'Expand Test Savings')]]"))
    savingsRow.click()
    Thread.sleep(300)

    // Should see "+ Add" button in expanded view
    card.findElement(By.xpath(".//button[contains(text(),'+ Add')]")).isDisplayed shouldBe true
  }

  it should "add a savings transaction" in {
    ensurePeriodExists()
    addSavingsAccount("Add Txn Savings", Some(500))

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card       = findCard("Planned Savings")
    // Expand the savings account
    val savingsRow = card.findElement(By.xpath(".//tr[.//td[contains(text(),'Add Txn Savings')]]"))
    savingsRow.click()
    Thread.sleep(300)

    // Click + Add to show transaction form
    click(card, "+ Add")

    // Fill in transaction
    val addRow      = card.findElement(By.cssSelector("tr.table-info"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys("Test deposit")
    val amountInput = addRow.findElement(By.cssSelector("input[type='number']"))
    amountInput.clear()
    amountInput.sendKeys("50")
    click(addRow, "Add")

    // Transaction should appear
    card.getText should include("Test deposit")
  }

  it should "delete a savings transaction" in {
    ensurePeriodExists()
    addSavingsAccount("Delete Txn Savings", Some(500))

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card       = findCard("Planned Savings")
    // Expand the savings account
    val savingsRow = card.findElement(By.xpath(".//tr[.//td[contains(text(),'Delete Txn Savings')]]"))
    savingsRow.click()
    Thread.sleep(300)

    // Add a transaction to delete
    click(card, "+ Add")
    val addRow      = card.findElement(By.cssSelector("tr.table-info"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys("To delete txn")
    val amountInput = addRow.findElement(By.cssSelector("input[type='number']"))
    amountInput.clear()
    amountInput.sendKeys("10")
    click(addRow, "Add")

    card.getText should include("To delete txn")

    // Find and delete the transaction
    val txnRow = card.findElement(By.xpath(".//tr[.//td[contains(text(),'To delete txn')]]"))
    click(txnRow, "×")

    card.getText should not include "To delete txn"
  }

  it should "collapse expanded savings account" in {
    ensurePeriodExists()
    addSavingsAccount("Collapse Test Savings", Some(500))

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card = findCard("Planned Savings")
    // Expand
    card.findElement(By.xpath(".//tr[.//td[contains(text(),'Collapse Test Savings')]]")).click()
    Thread.sleep(300)

    card.findElements(By.xpath(".//button[contains(text(),'+ Add')]")).size() shouldBe 1

    // Collapse - need to re-find element as DOM was updated
    card.findElement(By.xpath(".//tr[.//td[contains(text(),'Collapse Test Savings')]]")).click()
    Thread.sleep(300)

    card.findElements(By.xpath(".//button[contains(text(),'+ Add')]")).size() shouldBe 0
  }

  it should "show remaining to save in footer" in {
    ensurePeriodExists()
    addSavingsAccount("Footer Savings Test", Some(500))

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card       = findCard("Planned Savings")
    val footerText = card.findElement(By.cssSelector(".card-footer")).getText
    footerText should include("Remaining to Save")
  }
}
