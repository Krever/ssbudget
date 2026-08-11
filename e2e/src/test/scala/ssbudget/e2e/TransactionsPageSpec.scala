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

    foldToggle.getText should include("▾")
    categoriesCard.getText should include("Avg / mo")
  }

  it should "fold away the table, and stay folded across a reload" in {
    driver.get(s"$baseUrl/transactions")
    waitForPage("Transactions")

    foldToggle.click()
    Thread.sleep(300)

    val folded = categoriesCard
    folded.getText should include("▸")
    folded.getText should not include "Avg / mo"         // the table is gone
    assertAbsent(folded, By.cssSelector(".card-footer")) // so is the add-category row
    assertAbsent(folded, By.xpath(".//button[contains(.,'budget types')]"))

    driver.navigate().refresh()
    waitForPage("Transactions")

    foldToggle.getText should include("▸")
    categoriesCard.getText should not include "Avg / mo"
  }

  it should "unfold again, and stay unfolded across a reload" in {
    driver.get(s"$baseUrl/transactions")
    waitForPage("Transactions")

    foldToggle.click() // fold
    Thread.sleep(300)
    foldToggle.click() // unfold
    Thread.sleep(300)

    categoriesCard.getText should include("Avg / mo")

    driver.navigate().refresh()
    waitForPage("Transactions")

    foldToggle.getText should include("▾")
    categoriesCard.getText should include("Avg / mo")
  }
}
