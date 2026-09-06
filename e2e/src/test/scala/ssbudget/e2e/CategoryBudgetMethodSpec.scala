package ssbudget.e2e

import org.openqa.selenium.support.ui.Select
import org.openqa.selenium.{By, Keys, WebElement}

import scala.jdk.CollectionConverters.*

/** Choosing how a category's monthly figure is derived, from the settings strip under its row in the categories card.
  *
  * The seeded history is deliberately spiky — one month nearly ten times the others — so every setting produces a DIFFERENT figure and the assertions
  * can't pass by accident: mean 400, median 200, mean of the last two months 550, and 1,234 typed by hand.
  */
class CategoryBudgetMethodSpec extends E2ESpec {

  private val categoryName = "E2E Spiky Groceries"

  /** The seeded category's row. Re-found on every call: saving a setting re-renders the table and stales anything held from before. */
  private def row: WebElement =
    categoriesCard.findElement(By.xpath(s".//tbody/tr[td[1][contains(.,'$categoryName')]]"))

  private def budgetCell   = row.findElement(By.xpath("./td[2]"))
  private def expectedCell = row.findElement(By.xpath("./td[3]"))

  /** Loads the page and opens the row's settings strip — anywhere on the row does it. Navigating resets the card's expansion state, so the two always
    * go together and every test starts from the same place.
    */
  private def openSettings(): Unit = {
    openTransactions()
    row.click()
  }

  /** The strip itself — the row right under the category's, which only exists while it's open. */
  private def settings: WebElement =
    row.findElement(By.xpath("./following-sibling::tr[1][contains(@class,'budget-settings')]"))

  /** A control in the strip, found by the label in front of it rather than by position. */
  private def field(name: String): WebElement =
    settings.findElement(By.xpath(s".//div[span[text()='$name']]/*[self::select or self::input]"))

  /** Pick an option, retrying: each save re-renders the table, so a control found a moment earlier can go stale mid-click. Selecting is idempotent,
    * which is what makes retrying safe.
    */
  private def choose(name: String, value: String): Unit = eventually(new Select(field(name)).selectByValue(value))

  /** Retype a field and commit it, in ONE keyboard session. Enter blurs the field, and blurring is what saves — so `clear()` can't be used here: it
    * blurs too, which saves halfway through and re-renders the row out from under the very element being typed into. Retried for the same reason
    * [[choose]] is, and idempotent because each attempt re-reads what's currently in the field before replacing it.
    */
  private def retype(name: String, text: String): Unit = eventually {
    val f        = field(name)
    val existing = Option(f.getAttribute("value")).getOrElse("")
    f.sendKeys(Keys.END.toString + Keys.BACK_SPACE.toString * existing.length + text + Keys.ENTER)
  }

  /** Asserts a WHOLE cell, not a substring: "400.00 PLN" contains "-400.00 PLN"'s digits, so a sign flip would slip past `include`. */
  private def cellShouldBe(cell: => WebElement, text: String): Unit = eventually(cell.getText shouldBe text)

  private def expectedShouldBe(amount: String): Unit = cellShouldBe(expectedCell, s"$amount PLN")
  private def summaryShouldBe(text: String): Unit    = cellShouldBe(budgetCell, text)

  override def beforeAll(): Unit = {
    super.beforeAll()
    // Left OFF as a budget on purpose: this spec is about deriving the figure, and a live budget would move the free-money totals other specs assert.
    // Negative amounts: a transaction's sign is the direction the money moved, so an expense category's history is made of outflows.
    val id = TransactionSeed.addCategory(categoryName)
    TransactionSeed.addTransaction("Spiky E2E cheap", -10000, bookedAt = TransactionSeed.monthsAgo(3), categoryId = Some(id))
    TransactionSeed.addTransaction("Spiky E2E holiday", -90000, bookedAt = TransactionSeed.monthsAgo(2), categoryId = Some(id))
    TransactionSeed.addTransaction("Spiky E2E normal", -20000, bookedAt = TransactionSeed.monthsAgo(1), categoryId = Some(id))
  }

  "A category" should "start as an average over all of its history" in {
    openTransactions()
    expectedShouldBe("400.00") // (100 + 900 + 200) / 3
  }

  it should "take the median instead, ignoring the one month that inflates the mean" in {
    openSettings()
    choose("Method", "median")
    expectedShouldBe("200.00") // the middle of 100, 200, 900

    // And the choice is stored, not just shown.
    openSettings()
    eventually(new Select(field("Method")).getFirstSelectedOption.getText shouldBe "Median")
    expectedShouldBe("200.00")
  }

  it should "average only the months inside the lookback window" in {
    openSettings()
    choose("Method", "average")
    expectedShouldBe("400.00")

    retype("Months", "2")
    expectedShouldBe("550.00") // (900 + 200) / 2 — the cheap month is out of range

    // Blanking the window goes back to all of history.
    retype("Months", "")
    expectedShouldBe("400.00")
  }

  it should "take a figure typed by hand, and stop consulting history" in {
    openSettings()
    choose("Method", "fixed")

    // Switching to Fixed seeds the amount with the statistic it replaces rather than zeroing it, and locks the window it no longer reads.
    eventually(field("Amount").getAttribute("value") shouldBe "400")
    eventually(field("Months").isEnabled shouldBe false)

    retype("Amount", "1234")

    openSettings()
    eventually(new Select(field("Method")).getFirstSelectedOption.getText shouldBe "Fixed")
    eventually(field("Amount").getAttribute("value") shouldBe "1234")
    expectedShouldBe("1,234.00")
  }

  /** The history chart under the controls: one dot per completed month, dimmed when the window excludes it. Charting the same series the statistic
    * runs over is the point — it's what makes the Months box pickable rather than guessable.
    */
  it should "chart the months behind the figure, dimming the ones the window leaves out" in {
    openSettings()
    choose("Method", "average")
    retype("Months", "")

    def dots     = settings.findElements(By.cssSelector("svg circle")).asScala.toList
    def included = dots.filter(_.getAttribute("opacity") == "1")

    // Three months of history, all of them counted while the window is open.
    eventually(dots.size shouldBe 3)
    eventually(included.size shouldBe 3)
    // Each dot carries its own readout rather than labelling every point on the chart.
    eventually(dots.last.findElement(By.cssSelector("title")).getAttribute("textContent") should endWith("200.00"))

    // Narrowing the window dims what it drops, without losing it from the chart.
    retype("Months", "2")
    eventually(dots.size shouldBe 3)
    eventually(included.size shouldBe 2)
  }

  /** The read-only line the table shows in place of the four controls. Restores the category to "not a budget" at the end so the free-money totals
    * other specs assert stay where they were.
    */
  it should "summarise its settings on the row, so the table stays scannable" in {
    openSettings()
    summaryShouldBe("—") // not a budget: nothing consumes its figure

    choose("Type", "steady")
    choose("Method", "average")
    retype("Months", "") // state this test asserts, rather than inheriting whatever ran before it
    summaryShouldBe("Steady · avg")

    choose("Method", "median")
    retype("Months", "6")
    summaryShouldBe("Steady · median 6")

    choose("Type", "off")
    summaryShouldBe("—")
  }
}
