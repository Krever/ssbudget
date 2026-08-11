package ssbudget.e2e

import org.openqa.selenium.{By, WebElement}
import ssbudget.shared.model.CategoryBudgetType

import scala.jdk.CollectionConverters.*

/** Drilling from a category into the transactions behind it, from both entry points: the Budget page's inline expansion and clicking a period spend
  * figure on the Transactions page. Also covers that a filtered transactions URL is honoured on a cold load, since that's what the link out produces.
  */
class CategoryDrillDownSpec extends E2ESpec {

  /** A budget with no spend has nothing left to reserve, so the card's default "Hide paid" filter would hide it. */
  private def showAllBudgets(card: WebElement): Unit = {
    val toggle = card.findElement(By.cssSelector("input#hidePaidBudgets"))
    if toggle.isSelected then {
      toggle.click()
      Thread.sleep(300)
    }
  }

  private def budgetRow(card: WebElement, categoryName: String): WebElement =
    card.findElement(By.xpath(s".//span[contains(., '$categoryName')]"))

  /** Open the Budget page's Category Budgets card with every budget visible. */
  private def openBudgetsCard(): WebElement = {
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")
    val card = findCard("Category Budgets")
    showAllBudgets(card)
    card
  }

  "A Category Budget row" should "expand to show the transactions behind its spend" in {
    ensurePeriodExists()
    val categoryId = TransactionSeed.addCategory("Drilldown Groceries", Some(CategoryBudgetType.Steady))
    TransactionSeed.addTransaction("Biedronka E2E", -14230, categoryId = Some(categoryId))
    TransactionSeed.addTransaction("Lidl E2E", -8810, categoryId = Some(categoryId))

    val card   = openBudgetsCard()
    val header = budgetRow(card, "Drilldown Groceries")
    header.getText should include("▸")

    header.click()
    Thread.sleep(700)

    val text = card.getText
    text should include("▾")
    text should include("Biedronka E2E")
    text should include("Lidl E2E")
    text should include("142.30") // the outflow, shown with its sign
    text should include("open in Transactions")
  }

  it should "say so when the category has no transactions this period" in {
    ensurePeriodExists()
    TransactionSeed.addCategory("Drilldown Empty", Some(CategoryBudgetType.Steady))

    val card = openBudgetsCard()
    budgetRow(card, "Drilldown Empty").click()
    Thread.sleep(700)

    card.getText should include("No transactions this period")
  }

  it should "link out to the transactions list filtered to that category and period" in {
    ensurePeriodExists()
    TransactionSeed.addCategory("Drilldown Rent", Some(CategoryBudgetType.Bill))

    val card = openBudgetsCard()
    budgetRow(card, "Drilldown Rent").click()
    Thread.sleep(500)

    card.findElement(By.xpath(".//a[contains(text(),'open in Transactions')]")).click()
    waitForPage("Transactions")

    val url = driver.getCurrentUrl
    url should include("/transactions?")
    url should include("category=")
    url should include("month=current-period")

    // The URL is the source of truth for the filters, so the controls must land on it.
    val selected = selectedOptionTexts(driver.findElement(By.id("tx-filters")))
    selected should contain("Drilldown Rent")
    selected should contain("Current period")
  }

  "A period spend figure" should "become a drill-through link once the category has spend" in {
    ensurePeriodExists()
    val categoryId = TransactionSeed.addCategory("Drilldown Fuel")
    TransactionSeed.addTransaction("Orlen Drill E2E", -25000, categoryId = Some(categoryId))

    driver.get(s"$baseUrl/transactions")
    waitForPage("Transactions")

    val card = findCard("Categories & monthly averages")
    val row  = card.findElement(By.xpath(".//tr[.//span[text()='Drilldown Fuel']]"))
    row.findElement(By.xpath(".//a[contains(text(),'250')]")).click()
    Thread.sleep(700)

    // The filters below now target that category over the current period, and the table shows its transaction.
    selectedOptionTexts(driver.findElement(By.id("tx-filters"))) should contain("Drilldown Fuel")
    txTable.getText should include("Orlen Drill E2E")
  }

  it should "stay plain text while the category has no spend" in {
    ensurePeriodExists()
    TransactionSeed.addCategory("Drilldown Unspent")

    driver.get(s"$baseUrl/transactions")
    waitForPage("Transactions")

    // Nothing to drill into, so the figures are deliberately not links.
    val card = findCard("Categories & monthly averages")
    val row  = card.findElement(By.xpath(".//tr[.//span[text()='Drilldown Unspent']]"))
    assertAbsent(row, By.cssSelector("a"))
  }

  "A filtered transactions URL" should "apply its filters on a cold load" in {
    ensurePeriodExists()

    driver.get(s"$baseUrl/transactions?month=current-period&hideInternal=false")
    waitForPage("Transactions")

    val filters = driver.findElement(By.id("tx-filters"))
    selectedOptionTexts(filters) should contain("Current period")
    filters.findElement(By.cssSelector("input#hideInternal")).isSelected shouldBe false
  }
}
