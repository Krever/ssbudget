package ssbudget.e2e

import org.openqa.selenium.By

import scala.jdk.CollectionConverters.*

class TransactionsPageSpec extends E2ESpec {

  // `contains(., ...)` not `contains(text(), ...)`: the header span's first text node is the ▾/▸ caret, and `text()` in an argument position only
  // yields that first node.
  private def foldToggle = categoriesCard.findElement(By.xpath(".//span[contains(., 'Categories & monthly budgets')]"))

  "The categories card" should "start expanded" in {
    openTransactions()

    textShouldAppear(foldToggle, "▾")
    textShouldAppear(categoriesCard, "Expected / mo")
  }

  it should "fold away the table, and stay folded across a reload" in {
    openTransactions()

    foldToggle.click()
    textShouldAppear(foldToggle, "▸")

    textShouldDisappear(categoriesCard, "Expected / mo")         // the table is gone
    assertAbsent(categoriesCard, By.cssSelector(".card-footer")) // so is the add-category row
    assertAbsent(categoriesCard, By.xpath(".//button[contains(.,'budget types')]"))

    driver.navigate().refresh()
    waitForPage("Transactions")

    textShouldAppear(foldToggle, "▸")
    textShouldDisappear(categoriesCard, "Expected / mo")
  }

  it should "unfold again, and stay unfolded across a reload" in {
    openTransactions()

    foldToggle.click() // fold
    textShouldAppear(foldToggle, "▸")
    foldToggle.click() // unfold
    textShouldAppear(categoriesCard, "Expected / mo")

    driver.navigate().refresh()
    waitForPage("Transactions")

    textShouldAppear(foldToggle, "▾")
    textShouldAppear(categoriesCard, "Expected / mo")
  }
}
