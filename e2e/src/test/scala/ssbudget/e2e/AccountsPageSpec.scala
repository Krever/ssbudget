package ssbudget.e2e

import org.openqa.selenium.By
import scala.jdk.CollectionConverters.*

class AccountsPageSpec extends E2ESpec {

  // ============ Bank Accounts ============

  "Accounts page" should "load and show initial bank accounts" in {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val bankCard  = findCard("Bank Accounts")
    val tableRows = rows(bankCard)
    tableRows.size should be >= 2
  }

  it should "show expected bank account names" in {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val bankCard = findCard("Bank Accounts")
    val names    = bankCard.findElements(By.cssSelector("tbody tr td:first-child")).asScala.map(_.getText).toList
    names should contain("Main PLN")
    names should contain("Euro Account")
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

  it should "show total balance and exchange rate in footer" in {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val bankCard   = findCard("Bank Accounts")
    val footerText = bankCard.findElement(By.cssSelector(".card-footer")).getText
    footerText should include("Total Balance (PLN)")
    footerText should include("EUR/PLN")
  }

  // ============ Savings Accounts ============

  it should "show savings accounts section" in {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val savingsCard = findCard("Savings Accounts")
    savingsCard.isDisplayed shouldBe true
  }

  it should "show initial savings accounts" in {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val savingsCard = findCard("Savings Accounts")
    val tableRows   = rows(savingsCard)
    tableRows.size should be >= 1

    val names = savingsCard.findElements(By.cssSelector("tbody tr td:first-child")).asScala.map(_.getText).toList
    names should contain("Emergency Fund")
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
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val savingsCard = findCard("Savings Accounts")
    val firstRow    = savingsCard.findElement(By.cssSelector("tbody tr"))
    click(firstRow, "Edit")

    val editRow = savingsCard.findElement(By.cssSelector("tbody tr.table-warning"))
    editRow.isDisplayed shouldBe true

    val targetInput = editRow.findElement(By.cssSelector("input[type='number']"))
    targetInput.clear()
    targetInput.sendKeys("999")
    click(editRow, "Save")

    rows(savingsCard).head.getText should include("999")
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
