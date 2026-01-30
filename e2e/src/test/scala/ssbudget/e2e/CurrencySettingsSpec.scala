package ssbudget.e2e

import org.openqa.selenium.By
import scala.jdk.CollectionConverters.*

class CurrencySettingsSpec extends E2ESpec {

  "Settings page" should "show Currencies card with PLN and EUR" in {
    driver.get(s"$baseUrl/settings")
    waitFor.until(_ => driver.findElement(By.tagName("h2")).getText.contains("Settings"))

    val currenciesCard = findCardByH5("Currencies")
    currenciesCard.isDisplayed shouldBe true

    val cardText = currenciesCard.getText
    cardText should include("PLN")
    cardText should include("Polish Zloty")
    cardText should include("EUR")
    cardText should include("Euro")
    cardText should include("Primary")
  }

  it should "show PLN as primary currency by default" in {
    driver.get(s"$baseUrl/settings")
    waitFor.until(_ => driver.findElement(By.tagName("h2")).getText.contains("Settings"))

    val currenciesCard = findCardByH5("Currencies")
    val plnRow         = currenciesCard.findElement(By.xpath(".//tr[contains(.,'PLN')]"))
    plnRow.getText should include("Primary")
  }

  it should "have Refresh Rates button" in {
    driver.get(s"$baseUrl/settings")
    waitFor.until(_ => driver.findElement(By.tagName("h2")).getText.contains("Settings"))

    val currenciesCard = findCardByH5("Currencies")
    val refreshButton  = currenciesCard.findElement(By.xpath(".//button[contains(.,'Refresh Rates')]"))
    refreshButton.isDisplayed shouldBe true
  }

  it should "refresh exchange rates and display them" in {
    driver.get(s"$baseUrl/settings")
    waitFor.until(_ => driver.findElement(By.tagName("h2")).getText.contains("Settings"))

    val currenciesCard = findCardByH5("Currencies")

    // Click refresh rates button
    click(currenciesCard, "Refresh Rates")
    Thread.sleep(2000) // Wait for API call to external service

    // Wait for success message
    waitFor.until { _ =>
      val alerts = driver.findElements(By.cssSelector(".alert-success")).asScala
      alerts.exists(_.getText.contains("Exchange rates refreshed"))
    }

    // Verify EUR row has a rate displayed (not N/A)
    val eurRow     = currenciesCard.findElement(By.xpath(".//tr[contains(.,'EUR')]"))
    val eurRowText = eurRow.getText
    eurRowText should not include "N/A"

    // Verify the rate is a valid number (should be something like "4.1234")
    val rateCell = eurRow.findElement(By.cssSelector("td.text-end.font-monospace"))
    val rateText = rateCell.getText.trim
    rateText should fullyMatch regex """\d+\.\d{4}"""
  }

  it should "add a new currency" in {
    driver.get(s"$baseUrl/settings")
    waitFor.until(_ => driver.findElement(By.tagName("h2")).getText.contains("Settings"))

    val currenciesCard = findCardByH5("Currencies")
    val initialRows    = currenciesCard.findElements(By.cssSelector("tbody tr")).asScala.size

    val input = currenciesCard.findElement(By.cssSelector("input[type='text']"))
    input.sendKeys("USD")
    click(currenciesCard, "Add Currency")
    Thread.sleep(500)

    val newRows = currenciesCard.findElements(By.cssSelector("tbody tr")).asScala.size
    newRows shouldBe (initialRows + 1)
    currenciesCard.getText should include("USD")
    currenciesCard.getText should include("US Dollar")
  }

  it should "set a different currency as primary" in {
    // First ensure USD is enabled
    driver.get(s"$baseUrl/settings")
    waitFor.until(_ => driver.findElement(By.tagName("h2")).getText.contains("Settings"))

    val currenciesCard = findCardByH5("Currencies")

    // Add USD if not present
    if !currenciesCard.getText.contains("USD") then {
      val input = currenciesCard.findElement(By.cssSelector("input[type='text']"))
      input.sendKeys("USD")
      click(currenciesCard, "Add Currency")
      Thread.sleep(500)
    }

    // Set USD as primary
    val usdRow = currenciesCard.findElement(By.xpath(".//tr[contains(.,'USD')]"))
    click(usdRow, "Set Primary")
    Thread.sleep(500)

    // Verify USD is now primary
    driver.get(s"$baseUrl/settings")
    waitFor.until(_ => driver.findElement(By.tagName("h2")).getText.contains("Settings"))

    val updatedCard   = findCardByH5("Currencies")
    val updatedUsdRow = updatedCard.findElement(By.xpath(".//tr[contains(.,'USD')]"))
    updatedUsdRow.getText should include("Primary")

    // The old primary (PLN) should no longer have the primary badge in its row
    val plnRow = updatedCard.findElement(By.xpath(".//tr[contains(.,'PLN')]"))
    plnRow.findElements(By.xpath(".//span[contains(@class,'text-bg-primary')]")).asScala.size shouldBe 0

    // Restore PLN as primary for other tests
    click(plnRow, "Set Primary")
    Thread.sleep(500)
  }

  it should "remove a non-primary currency" in {
    driver.get(s"$baseUrl/settings")
    waitFor.until(_ => driver.findElement(By.tagName("h2")).getText.contains("Settings"))

    val currenciesCard = findCardByH5("Currencies")

    // Add GBP to remove
    if !currenciesCard.getText.contains("GBP") then {
      val input = currenciesCard.findElement(By.cssSelector("input[type='text']"))
      input.sendKeys("GBP")
      click(currenciesCard, "Add Currency")
      Thread.sleep(500)
    }

    val rowsBefore = currenciesCard.findElements(By.cssSelector("tbody tr")).asScala.size
    val gbpRow     = currenciesCard.findElement(By.xpath(".//tr[contains(.,'GBP')]"))
    click(gbpRow, "Remove")
    Thread.sleep(500)

    val rowsAfter = currenciesCard.findElements(By.cssSelector("tbody tr")).asScala.size
    rowsAfter shouldBe (rowsBefore - 1)
    currenciesCard.getText should not include "GBP"
  }

  it should "not show remove button for primary currency" in {
    driver.get(s"$baseUrl/settings")
    waitFor.until(_ => driver.findElement(By.tagName("h2")).getText.contains("Settings"))

    val currenciesCard = findCardByH5("Currencies")
    val plnRow         = currenciesCard.findElement(By.xpath(".//tr[contains(.,'PLN') and contains(.,'Primary')]"))
    val removeButtons  = plnRow.findElements(By.xpath(".//button[contains(.,'Remove')]")).asScala
    removeButtons.size shouldBe 0
  }

  it should "show enabled currencies in account creation dropdown" in {
    // First add USD currency if not present
    driver.get(s"$baseUrl/settings")
    waitFor.until(_ => driver.findElement(By.tagName("h2")).getText.contains("Settings"))

    val currenciesCard = findCardByH5("Currencies")
    if !currenciesCard.getText.contains("USD") then {
      val input = currenciesCard.findElement(By.cssSelector("input[type='text']"))
      input.sendKeys("USD")
      click(currenciesCard, "Add Currency")
      Thread.sleep(500)
    }

    // Navigate to accounts page
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val bankCard = findCard("Bank Accounts")
    click(bankCard, "+ Add")

    val addRow   = bankCard.findElement(By.cssSelector("tbody tr.table-primary"))
    val dropdown = addRow.findElement(By.cssSelector("select"))
    val options  = dropdown.findElements(By.tagName("option")).asScala.map(_.getText).toList

    options should contain("PLN")
    options should contain("EUR")
    options should contain("USD")

    click(addRow, "Cancel")
  }
}
