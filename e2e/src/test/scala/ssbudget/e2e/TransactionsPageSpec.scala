package ssbudget.e2e

import org.openqa.selenium.By

import scala.jdk.CollectionConverters.*

class TransactionsPageSpec extends E2ESpec {

  private def categoriesCard = findCard("Categories & monthly averages")

  // `contains(., ...)` not `contains(text(), ...)`: the header span's first text node is the ▾/▸ caret, and `text()` in an argument position only
  // yields that first node.
  private def foldToggle = categoriesCard.findElement(By.xpath(".//span[contains(., 'Categories & monthly averages')]"))

  "The categories card" should "start expanded" in {
    driver.get(s"$baseUrl/transactions")
    waitForPage("Transactions")

    textShouldAppear(foldToggle, "▾")
    textShouldAppear(categoriesCard, "Avg / mo")
  }

  it should "fold away the table, and stay folded across a reload" in {
    driver.get(s"$baseUrl/transactions")
    waitForPage("Transactions")

    foldToggle.click()
    textShouldAppear(foldToggle, "▸")

    textShouldDisappear(categoriesCard, "Avg / mo")              // the table is gone
    assertAbsent(categoriesCard, By.cssSelector(".card-footer")) // so is the add-category row
    assertAbsent(categoriesCard, By.xpath(".//button[contains(.,'budget types')]"))

    driver.navigate().refresh()
    waitForPage("Transactions")

    textShouldAppear(foldToggle, "▸")
    textShouldDisappear(categoriesCard, "Avg / mo")
  }

  it should "unfold again, and stay unfolded across a reload" in {
    driver.get(s"$baseUrl/transactions")
    waitForPage("Transactions")

    foldToggle.click() // fold
    textShouldAppear(foldToggle, "▸")
    foldToggle.click() // unfold
    textShouldAppear(categoriesCard, "Avg / mo")

    driver.navigate().refresh()
    waitForPage("Transactions")

    textShouldAppear(foldToggle, "▾")
    textShouldAppear(categoriesCard, "Avg / mo")
  }
}
