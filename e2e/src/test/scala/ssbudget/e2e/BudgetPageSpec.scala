package ssbudget.e2e

import org.openqa.selenium.By
import scala.jdk.CollectionConverters.*

class BudgetPageSpec extends E2ESpec {

  "Budget page" should "load planned items and estimated expenses" in {
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val cardTexts = driver.findElements(By.cssSelector(".card")).asScala.map(_.getText).toList
    cardTexts.exists(_.contains("Planned Items")) shouldBe true
    cardTexts.exists(_.contains("Estimated Expenses")) shouldBe true
  }

  it should "add a new planned expense" in {
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
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card        = findCard("Planned Items")
    val pendingRows = card.findElements(By.xpath(".//tr[.//span[contains(text(),'Pending')]]")).asScala.toList

    if pendingRows.nonEmpty then {
      click(pendingRows.head, "Pay")
      click(card.findElement(By.cssSelector("tr.table-info")), "Save")
      rows(card).count(_.getText.contains("Paid")) should be >= 1
    }
  }

  it should "pay expense with overridden amount" in {
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card        = findCard("Planned Items")
    val pendingRows = card.findElements(By.xpath(".//tr[.//span[contains(text(),'Pending')]]")).asScala.toList

    if pendingRows.nonEmpty then {
      click(pendingRows.head, "Pay")
      val editRow = card.findElement(By.cssSelector("tr.table-info"))
      val input   = editRow.findElement(By.cssSelector("input[type='number']"))
      input.clear()
      input.sendKeys("99.99")
      click(editRow, "Save")

      rows(card).exists(_.getText.contains("99.99")) shouldBe true
    }
  }

  it should "edit and delete a budget item" in {
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card = findCard("Estimated Expenses")
    click(card, "+ Add")

    val addRow = card.findElement(By.cssSelector("tr.table-primary"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys("To Delete")
    addRow.findElement(By.cssSelector("input[type='number']")).sendKeys("100")
    click(addRow, "Add")

    val toDelete = card.findElement(By.xpath(".//tr[.//td[contains(text(),'To Delete')]]"))
    click(toDelete, "Edit")
    click(card.findElement(By.cssSelector("tr.table-warning")), "Del")

    rows(card).exists(_.getText.contains("To Delete")) shouldBe false
  }

  // ============ Planned Savings ============

  it should "show planned savings card" in {
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val cardTexts = driver.findElements(By.cssSelector(".card")).asScala.map(_.getText).toList
    cardTexts.exists(_.contains("Planned Savings")) shouldBe true
  }

  it should "show savings accounts with targets" in {
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card = findCard("Planned Savings")
    card.getText should include("Emergency Fund")
    card.getText should include("Target")
    card.getText should include("Saved")
    card.getText should include("Remaining")
  }

  it should "expand savings account to show transactions" in {
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card       = findCard("Planned Savings")
    // Find a savings account row and click to expand
    val savingsRow = card.findElement(By.xpath(".//tr[.//td[contains(text(),'Emergency Fund')]]"))
    savingsRow.click()
    Thread.sleep(300)

    // Should see "+ Add" button in expanded view
    card.findElement(By.xpath(".//button[contains(text(),'+ Add')]")).isDisplayed shouldBe true
  }

  it should "add a savings transaction" in {
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card       = findCard("Planned Savings")
    // Expand the savings account
    val savingsRow = card.findElement(By.xpath(".//tr[.//td[contains(text(),'Emergency Fund')]]"))
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
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card       = findCard("Planned Savings")
    // Expand the savings account
    val savingsRow = card.findElement(By.xpath(".//tr[.//td[contains(text(),'Emergency Fund')]]"))
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
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card = findCard("Planned Savings")
    // Expand
    card.findElement(By.xpath(".//tr[.//td[contains(text(),'Emergency Fund')]]")).click()
    Thread.sleep(300)

    card.findElements(By.xpath(".//button[contains(text(),'+ Add')]")).size() shouldBe 1

    // Collapse - need to re-find element as DOM was updated
    card.findElement(By.xpath(".//tr[.//td[contains(text(),'Emergency Fund')]]")).click()
    Thread.sleep(300)

    card.findElements(By.xpath(".//button[contains(text(),'+ Add')]")).size() shouldBe 0
  }

  it should "show remaining to save in footer" in {
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card       = findCard("Planned Savings")
    val footerText = card.findElement(By.cssSelector(".card-footer")).getText
    footerText should include("Remaining to Save")
  }
}
