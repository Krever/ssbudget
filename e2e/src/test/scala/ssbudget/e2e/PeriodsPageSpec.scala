package ssbudget.e2e

import org.openqa.selenium.{By, WebElement}
import scala.jdk.CollectionConverters.*

class PeriodsPageSpec extends E2ESpec {

  private def openPeriods(): Unit = {
    driver.get(s"$baseUrl/periods")
    waitForPage("Periods")
  }

  /** The history rows, once the retrospective has actually loaded. It's fetched per visit, so a count taken too early would see the placeholder row
    * and every comparison against it would be off by one.
    */
  private def loadedHistoryRows(): List[WebElement] = {
    eventually(findCardByDiv("Period History").getText should not include "Loading period history")
    rows(findCardByDiv("Period History"))
  }

  "Periods page" should "load and show period cards" in {
    openPeriods()

    val cardTexts = driver.findElements(By.cssSelector(".card")).asScala.map(_.getText).toList
    cardTexts.exists(_.contains("Current Period")) shouldBe true
    cardTexts.exists(_.contains("Period History")) shouldBe true
  }

  it should "start new period when none exists" in {
    openPeriods()

    val card = findCardByDiv("Current Period")

    // If no period, start one
    if card.getText.contains("No active period") then {
      click(card, "Start New Period")
      eventually(findCardByDiv("Current Period").getText should not include "No active period")
    }

    // Now should have an active period with progress bar
    val progressBar = card.findElement(By.cssSelector(".progress-bar"))
    progressBar.getAttribute("style") should include("width:")
  }

  it should "show progress bar for current period" in {
    ensurePeriodExists()
    openPeriods()

    val card        = findCardByDiv("Current Period")
    val progressBar = card.findElement(By.cssSelector(".progress-bar"))
    progressBar.getAttribute("style") should include("width:")
  }

  it should "show at least one period in history when period exists" in {
    ensurePeriodExists()
    openPeriods()

    loadedHistoryRows().size should be >= 1

    val card = findCardByDiv("Period History")
    card.findElements(By.xpath(".//span[contains(@class,'badge') and contains(text(),'Active')]")).size() shouldBe 1
  }

  it should "report what happened in each period" in {
    ensurePeriodExists()
    openPeriods()

    // The retrospective's columns: bank cash flow, plan execution, savings movement, closing balance.
    val card = findCardByDiv("Period History")
    List("In", "Out", "Net", "Planned paid", "Savings", "End balance").foreach(header => card.getText should include(header))
  }

  it should "expand a period row into its spend breakdown" in {
    ensurePeriodExists()
    openPeriods()

    val row = loadedHistoryRows().head
    row.getText should include("▸")
    row.click()

    eventually {
      val card = findCardByDiv("Period History")
      card.getText should include("▾")
      card.getText should include("planned expenses settled")
      card.getText should include("WHERE THE MONEY WENT") // text-uppercase, and getText reports what's rendered
    }
  }

  it should "close current period and start new one" in {
    ensurePeriodExists()
    openPeriods()

    val initialCount = loadedHistoryRows().size
    val currentCard  = findCardByDiv("Current Period")

    click(currentCard, "End Period & Start New")

    eventually {
      val history = findCardByDiv("Period History")
      history.getText should not include "Loading period history"
      rows(history).size shouldBe (initialCount + 1)
      history.findElements(By.xpath(".//span[contains(@class,'badge') and contains(text(),'Active')]")).size() shouldBe 1
    }
  }
}
