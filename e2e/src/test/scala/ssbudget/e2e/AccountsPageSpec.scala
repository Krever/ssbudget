package ssbudget.e2e

import org.openqa.selenium.By
import scala.jdk.CollectionConverters.*

class AccountsPageSpec extends E2ESpec {

  "Accounts page" should "load and show initial accounts" in {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val tableRows = driver.findElements(By.cssSelector("table.table tbody tr")).asScala.toList
    tableRows.size should be >= 3
  }

  it should "show expected account names" in {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val names = driver.findElements(By.cssSelector("table.table tbody tr td:first-child")).asScala.map(_.getText).toList
    names should contain("Main PLN")
    names should contain("Euro Account")
  }

  it should "add a new account" in {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val initialCount = driver.findElements(By.cssSelector("table.table tbody tr")).size()
    click(driver.findElement(By.cssSelector(".card-header")), "+ Add Account")

    val addRow = driver.findElement(By.cssSelector("tbody tr.table-primary"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys("Test Account")
    click(addRow, "Add")

    driver.findElements(By.cssSelector("table.table tbody tr")).size() shouldBe (initialCount + 1)
  }

  it should "cancel adding account" in {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val initialCount = driver.findElements(By.cssSelector("table.table tbody tr")).size()
    click(driver.findElement(By.cssSelector(".card-header")), "+ Add Account")
    click(driver.findElement(By.cssSelector("tbody tr.table-primary")), "Cancel")

    driver.findElements(By.cssSelector("table.table tbody tr")).size() shouldBe initialCount
  }

  it should "enter and cancel edit mode" in {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val firstRow    = driver.findElement(By.cssSelector("table.table tbody tr"))
    val initialName = firstRow.findElement(By.cssSelector("td:first-child")).getText

    click(firstRow, "Edit")
    driver.findElement(By.cssSelector("tbody tr.table-warning")).isDisplayed shouldBe true

    click(driver.findElement(By.cssSelector("tbody tr.table-warning")), "Cancel")
    driver.findElement(By.cssSelector("table.table tbody tr td:first-child")).getText shouldBe initialName
  }

  it should "show total balance and exchange rate in footer" in {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val footerText = driver.findElement(By.cssSelector(".card-footer")).getText
    footerText should include("Total Balance (PLN)")
    footerText should include("EUR/PLN")
  }
}
