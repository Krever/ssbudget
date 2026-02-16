package ssbudget.e2e

import org.openqa.selenium.{By, JavascriptExecutor}
import scala.jdk.CollectionConverters.*

class DashboardSpec extends E2ESpec {

  "Dashboard" should "load and show summary panel" in {
    ensurePeriodExists()
    addBankAccount("Test Account")

    driver.get(baseUrl)
    waitForPage("Dashboard")

    val cardTexts = driver.findElements(By.cssSelector(".card")).asScala.map(_.getText).toList
    cardTexts.exists(_.contains("BALANCE")) shouldBe true
    cardTexts.exists(_.contains("FREE")) shouldBe true
    cardTexts.exists(_.contains("DAYS")) shouldBe true
  }

  it should "update account balance via bulk edit" in {
    ensurePeriodExists()
    addBankAccount("Balance Test Account")

    driver.get(baseUrl)
    waitForPage("Dashboard")

    val card  = findCard("Accounts")
    click(card, "Edit Balances")
    val input = card.findElements(By.cssSelector("input[type='number']")).asScala.head
    input.clear()
    input.sendKeys("5000.00")
    click(card, "Save All")

    Thread.sleep(300)
    card.getText should include("5,000")
  }

  it should "cancel balance edit without saving" in {
    ensurePeriodExists()
    addBankAccount("Cancel Test Account")

    driver.get(baseUrl)
    waitForPage("Dashboard")

    val card         = findCard("Accounts")
    val initialTotal = card.findElement(By.cssSelector(".card-footer .font-monospace")).getText

    click(card, "Edit Balances")
    card.findElement(By.cssSelector("input[type='number']")).sendKeys("999999")
    click(card, "Cancel")

    card.findElement(By.cssSelector(".card-footer .font-monospace")).getText shouldBe initialTotal
  }

  it should "show cumulative savings for current period" in {
    ensurePeriodExists()
    addSavingsAccount("E2E Savings", targetAmount = Some(500))

    // Navigate to budget page and add a savings transaction
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val savingsCard = findCard("Planned Savings")
    // Click the savings account row to expand it
    val accountRow  = savingsCard.findElement(By.xpath(".//tbody/tr[contains(.,'E2E Savings')]"))
    accountRow.click()
    Thread.sleep(300)

    // Click "+ Add" to open the transaction form
    click(savingsCard, "+ Add")

    // Fill in the amount
    val txnRow      = savingsCard.findElement(By.cssSelector("tr.table-info"))
    val amountInput = txnRow.findElement(By.cssSelector("input[type='number']"))
    amountInput.clear()
    amountInput.sendKeys("200")
    click(txnRow, "Add")
    Thread.sleep(500)

    // Navigate to dashboard and verify "Saved" row
    driver.get(baseUrl)
    waitForPage("Dashboard")

    val summaryCard = driver.findElement(By.cssSelector(".card"))
    val cardText    = summaryCard.getText
    cardText should include("Saved")
    cardText should include("200")
  }

  it should "show cumulative one-time expenses for current period" in {
    ensurePeriodExists()

    // Navigate to budget page and add a one-time expense
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    click(findCard("One-Time Expenses"), "+ Add")
    val addRow = findCard("One-Time Expenses").findElement(By.cssSelector("tr.table-primary"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys("Test Purchase")
    addRow.findElement(By.cssSelector("input[type='number']")).sendKeys("350")
    click(addRow, "Add")
    Thread.sleep(500)

    // Navigate to dashboard and verify "One-Time Expenses" row
    driver.get(baseUrl)
    waitForPage("Dashboard")

    val summaryCard = driver.findElement(By.cssSelector(".card"))
    val cardText    = summaryCard.getText
    cardText should include("One-Time Expenses")
    cardText should include("350")
  }

  it should "copy summary to clipboard" in {
    ensurePeriodExists()
    addBankAccount("Clipboard Test Account")

    driver.get(baseUrl)
    waitForPage("Dashboard")

    // Grant clipboard permissions via CDP
    val cdpDriver = driver.asInstanceOf[org.openqa.selenium.chromium.HasCdp]
    cdpDriver.executeCdpCommand(
      "Browser.grantPermissions",
      java.util.Map.of(
        "permissions",
        java.util.List.of("clipboardReadWrite", "clipboardSanitizedWrite"),
        "origin",
        baseUrl,
      ),
    )

    val btn = driver.findElement(By.xpath("//button[contains(text(),'Copy Summary')]"))
    btn.click()
    Thread.sleep(500)

    // Button should show "Copied!" feedback
    btn.getText shouldBe "Copied!"

    // Read clipboard via JavaScript
    val js        = driver.asInstanceOf[JavascriptExecutor]
    val clipboard = js
      .executeAsyncScript(
        """var callback = arguments[arguments.length - 1];
          |navigator.clipboard.readText().then(callback).catch(function(e) { callback('ERROR: ' + e.message); });""".stripMargin,
      )
      .asInstanceOf[String]

    clipboard should include("Budget Update")
    clipboard should include("Balance:")
    clipboard should include("Free:")
    clipboard should include("Daily:")
  }
}
