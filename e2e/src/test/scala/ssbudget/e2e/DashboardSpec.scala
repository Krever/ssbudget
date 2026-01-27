package ssbudget.e2e

import org.openqa.selenium.By
import scala.jdk.CollectionConverters.*

class DashboardSpec extends E2ESpec {

  "Dashboard" should "load and show summary cards" in {
    driver.get(baseUrl)
    waitForPage("Dashboard")

    val cardTexts = driver.findElements(By.cssSelector(".card")).asScala.map(_.getText).toList
    cardTexts.exists(_.contains("Total Balance")) shouldBe true
    cardTexts.exists(_.contains("Free Money")) shouldBe true
    cardTexts.exists(_.contains("Daily Budget")) shouldBe true
  }

  it should "update account balance via bulk edit" in {
    driver.get(baseUrl)
    waitForPage("Dashboard")

    val card         = findCard("Accounts")
    val initialTotal = card.findElement(By.cssSelector(".card-footer .font-monospace")).getText

    click(card, "Edit Balances")
    val input = card.findElements(By.cssSelector("input[type='number']")).asScala.head
    input.clear()
    input.sendKeys("5000.00")
    click(card, "Save All")

    card.findElement(By.cssSelector(".card-footer .font-monospace")).getText should not equal initialTotal
  }

  it should "cancel balance edit without saving" in {
    driver.get(baseUrl)
    waitForPage("Dashboard")

    val card         = findCard("Accounts")
    val initialTotal = card.findElement(By.cssSelector(".card-footer .font-monospace")).getText

    click(card, "Edit Balances")
    card.findElement(By.cssSelector("input[type='number']")).sendKeys("999999")
    click(card, "Cancel")

    card.findElement(By.cssSelector(".card-footer .font-monospace")).getText shouldBe initialTotal
  }

  it should "have a copy summary button" in {
    driver.get(baseUrl)
    waitForPage("Dashboard")

    val btn = driver.findElement(By.xpath("//button[contains(text(),'Copy Summary')]"))
    btn.isDisplayed shouldBe true
  }
}
