package ssbudget.e2e

import org.openqa.selenium.By
import scala.jdk.CollectionConverters.*

class PeriodsPageSpec extends E2ESpec {

  "Periods page" should "load current period and history" in {
    driver.get(s"$baseUrl/periods")
    waitForPage("Periods")

    val cardTexts = driver.findElements(By.cssSelector(".card")).asScala.map(_.getText).toList
    cardTexts.exists(_.contains("Current Period")) shouldBe true
    cardTexts.exists(_.contains("Period History")) shouldBe true
  }

  it should "show progress bar for current period" in {
    driver.get(s"$baseUrl/periods")
    waitForPage("Periods")

    val card        = findCardByDiv("Current Period")
    val progressBar = card.findElement(By.cssSelector(".progress-bar"))
    progressBar.getAttribute("style") should include("width:")
  }

  it should "show at least one period in history" in {
    driver.get(s"$baseUrl/periods")
    waitForPage("Periods")

    val card        = findCardByDiv("Period History")
    val historyRows = rows(card)
    historyRows.size should be >= 1

    card.findElements(By.xpath(".//span[contains(@class,'badge') and contains(text(),'Active')]")).size() shouldBe 1
  }

  it should "close current period and start new one" in {
    driver.get(s"$baseUrl/periods")
    waitForPage("Periods")

    val historyCard  = findCardByDiv("Period History")
    val initialCount = rows(historyCard).size
    val currentCard  = findCardByDiv("Current Period")

    click(currentCard, "End Period")
    Thread.sleep(500)

    rows(historyCard).size shouldBe (initialCount + 1)
    historyCard.findElements(By.xpath(".//span[contains(@class,'badge') and contains(text(),'Active')]")).size() shouldBe 1
  }
}
