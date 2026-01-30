package ssbudget.e2e

import org.openqa.selenium.By
import scala.jdk.CollectionConverters.*

class AccountsPageSpec extends E2ESpec {

  // ============ Bank Accounts ============

  "Accounts page" should "load and show bank accounts card" in {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val cardTexts = driver.findElements(By.cssSelector(".card")).asScala.map(_.getText).toList
    cardTexts.exists(_.contains("Bank Accounts")) shouldBe true
  }

  it should "add a new bank account" in {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val bankCard     = findCard("Bank Accounts")
    val initialCount = rows(bankCard).size
    click(bankCard, "+ Add")

    val addRow = bankCard.findElement(By.cssSelector("tbody tr.table-primary"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys("Test Account")
    click(addRow, "Add")

    rows(bankCard).size shouldBe (initialCount + 1)
  }

  it should "cancel adding bank account" in {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val bankCard     = findCard("Bank Accounts")
    val initialCount = rows(bankCard).size
    click(bankCard, "+ Add")
    click(bankCard.findElement(By.cssSelector("tbody tr.table-primary")), "Cancel")

    rows(bankCard).size shouldBe initialCount
  }

  it should "enter and cancel edit mode for bank account" in {
    addBankAccount("Edit Test Account")

    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val bankCard    = findCard("Bank Accounts")
    val firstRow    = bankCard.findElement(By.cssSelector("tbody tr"))
    val initialName = firstRow.findElement(By.cssSelector("td:first-child")).getText

    click(firstRow, "Edit")
    bankCard.findElement(By.cssSelector("tbody tr.table-warning")).isDisplayed shouldBe true

    click(bankCard.findElement(By.cssSelector("tbody tr.table-warning")), "Cancel")
    bankCard.findElement(By.cssSelector("tbody tr td:first-child")).getText shouldBe initialName
  }

  it should "show total balance in footer" in {
    addBankAccount("Footer Test Account")

    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val bankCard   = findCard("Bank Accounts")
    val footerText = bankCard.findElement(By.cssSelector(".card-footer")).getText
    footerText should include("Total:")
  }

  // ============ Savings Accounts ============

  it should "show savings accounts section" in {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val savingsCard = findCard("Savings Accounts")
    savingsCard.isDisplayed shouldBe true
  }

  it should "add a new savings account" in {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val savingsCard  = findCard("Savings Accounts")
    val initialCount = rows(savingsCard).size
    click(savingsCard, "+ Add")

    val addRow = savingsCard.findElement(By.cssSelector("tbody tr.table-success"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys("New Savings")
    addRow.findElement(By.cssSelector("input[type='number']")).sendKeys("200")
    click(addRow, "Add")

    rows(savingsCard).size shouldBe (initialCount + 1)
    rows(savingsCard).exists(_.getText.contains("New Savings")) shouldBe true
  }

  it should "cancel adding savings account" in {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val savingsCard  = findCard("Savings Accounts")
    val initialCount = rows(savingsCard).size
    click(savingsCard, "+ Add")
    click(savingsCard.findElement(By.cssSelector("tbody tr.table-success")), "Cancel")

    rows(savingsCard).size shouldBe initialCount
  }

  it should "edit savings account" in {
    addSavingsAccount("Edit Savings Test", Some(500))

    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val savingsCard = findCard("Savings Accounts")
    val targetRow   = savingsCard.findElement(By.xpath(".//tr[.//td[contains(text(),'Edit Savings Test')]]"))
    click(targetRow, "Edit")

    val editRow = savingsCard.findElement(By.cssSelector("tbody tr.table-warning"))
    editRow.isDisplayed shouldBe true

    val targetInput = editRow.findElement(By.cssSelector("input[type='number']"))
    targetInput.clear()
    targetInput.sendKeys("999")
    click(editRow, "Save")

    savingsCard.getText should include("999")
  }

  it should "delete savings account" in {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    // First add an account to delete
    val savingsCard = findCard("Savings Accounts")
    click(savingsCard, "+ Add")

    val addRow = savingsCard.findElement(By.cssSelector("tbody tr.table-success"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys("To Delete")
    click(addRow, "Add")

    val countAfterAdd = rows(savingsCard).size
    rows(savingsCard).exists(_.getText.contains("To Delete")) shouldBe true

    // Now delete it
    val toDelete = savingsCard.findElement(By.xpath(".//tr[.//td[contains(text(),'To Delete')]]"))
    click(toDelete, "Edit")
    click(savingsCard.findElement(By.cssSelector("tbody tr.table-warning")), "Del")

    rows(savingsCard).size shouldBe (countAfterAdd - 1)
    rows(savingsCard).exists(_.getText.contains("To Delete")) shouldBe false
  }
}
