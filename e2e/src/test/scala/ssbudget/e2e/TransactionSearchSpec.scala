package ssbudget.e2e

import org.openqa.selenium.{By, Keys, WebElement}

import scala.jdk.CollectionConverters.*

/** The transaction search box: what it matches, how it reports approximate matches, and the filter widening that makes it usable from the page's
  * triage-first default view.
  */
class TransactionSearchSpec extends E2ESpec {

  private def searchBox: WebElement = driver.findElement(By.id("tx-search"))

  private def filtersRow: WebElement = driver.findElement(By.id("tx-filters"))

  private def search(term: String): Unit = {
    searchBox.clear()
    searchBox.sendKeys(term)
  }

  /** Seeded once per suite: creating these through the UI isn't possible (transactions only arrive by import), and every case here reads the same
    * set. The names are chosen to exercise folding (`Ł`), substring-inside-a-token, and multi-word terms.
    */
  private def seed(): Unit = {
    val categoryId = TransactionSeed.addCategory("Search Groceries")
    TransactionSeed.addTransaction("WOJCIECH PITUŁA", -25000, categoryId = Some(categoryId))
    TransactionSeed.addTransaction("Openai *Chatgpt Subscr", -9900, categoryId = Some(categoryId))
    TransactionSeed.addTransaction("JMP S.A. BIEDRONKA 4629", -14230, categoryId = Some(categoryId))
    TransactionSeed.addTransaction("Wise Charges for CARD-1", -500, categoryId = Some(categoryId))
  }

  "The search box" should "match across a diacritic that Unicode decomposition leaves alone" in {
    seed()
    openTransactions()

    // `Ł` is a letter in its own right, so neither NFD nor SQLite's remove_diacritics maps it to `l`. Without the explicit fold this finds nothing.
    search("pitula")

    textShouldAppear(txTable, "WOJCIECH PITUŁA")
    textShouldDisappear(txTable, "BIEDRONKA")
  }

  it should "match a substring inside a longer word" in {
    seed()
    openTransactions()

    search("gpt")

    textShouldAppear(txTable, "Chatgpt")
  }

  it should "narrow, not widen, as terms are added" in {
    seed()
    openTransactions()

    search("wise card")
    textShouldAppear(txTable, "Wise Charges")

    search("wise biedronka")
    textShouldDisappear(txTable, "Wise Charges")
  }

  it should "find a transaction through a typo, fenced off as a similar match" in {
    seed()
    openTransactions()

    search("bidronka")

    textShouldAppear(txTable, "BIEDRONKA")
    // Approximate hits are labelled and kept out of the footer's sum, so a guess never quietly lands in a total.
    textShouldAppear(txTable, "similar")
    textShouldAppear(txTable, "not counted in the sum")
  }

  it should "widen the triage filter while searching, and restore it when cleared" in {
    seed()
    openTransactions()

    // The page opens on its triage slice. A search is a hunt across everything, so the narrowing filter steps aside...
    selectedOptionTexts(filtersRow) should contain("Uncategorized")

    search("pitula")
    eventually(selectedOptionTexts(filtersRow) should contain("All categories"))
    textShouldAppear(txTable, "WOJCIECH PITUŁA")

    // ...and comes back, so the search is a detour rather than a one-way door.
    searchBox.sendKeys(Keys.ESCAPE)
    eventually(selectedOptionTexts(filtersRow) should contain("Uncategorized"))
  }

  // The widening is expressed as a default that follows the search term, not as remembered state, so it survives a round trip through the URL. A
  // stash held only in the page would be empty here and leave the user stranded on "All categories".
  it should "restore the triage filter after clearing a search that was reloaded" in {
    seed()
    openTransactions()

    search("pitula")
    eventually(selectedOptionTexts(filtersRow) should contain("All categories"))

    driver.navigate().refresh()
    waitForPage("Transactions")
    eventually(selectedOptionTexts(filtersRow) should contain("All categories"))

    searchBox.sendKeys(Keys.ESCAPE)
    eventually(selectedOptionTexts(filtersRow) should contain("Uncategorized"))
  }

  it should "survive a reload, so a search is linkable like every other filter" in {
    seed()
    openTransactions()

    search("pitula")
    textShouldAppear(txTable, "WOJCIECH PITUŁA")
    eventually(driver.getCurrentUrl should include("q=pitula"))

    driver.navigate().refresh()
    waitForPage("Transactions")

    searchBox.getAttribute("value") shouldBe "pitula"
    textShouldAppear(txTable, "WOJCIECH PITUŁA")
  }

  it should "report nothing found rather than falling back to everything" in {
    seed()
    openTransactions()

    search("qqqzzz")

    textShouldDisappear(txTable, "BIEDRONKA")
    eventually(rows(txTable).filter(_.getText.trim.nonEmpty) shouldBe empty)
  }
}
