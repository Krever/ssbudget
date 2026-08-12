package ssbudget.e2e

import cats.effect.unsafe.implicits.global
import org.openqa.selenium.By
import ssbudget.shared.model.{BankTransactionId, CategoryId}

/** The Dashboard's triage card: the few newest uncategorized transactions, categorizable without leaving the page. */
class DashboardTriageSpec extends E2ESpec {

  private def triageCard = findCard("To categorize")

  private def pageBody = driver.findElement(By.tagName("body"))

  /** Assert the category actually reached the server. Read there rather than from another page: navigating away cancels the in-flight write, so a
    * second page is a race, not a check.
    */
  private def categoryShouldBe(txId: BankTransactionId, categoryId: CategoryId): Unit =
    eventually(TestServers.repos.bankTransactions.findById(txId).unsafeRunSync().flatMap(_.categoryId) shouldBe Some(categoryId))

  "The Dashboard" should "list uncategorized transactions and categorize one in place" in {
    ensurePeriodExists()
    val categoryId = TransactionSeed.addCategory("Triage Food E2E")
    val tx         = TransactionSeed.addTransaction("Kebab Triage E2E", -3500)

    openDashboard()
    rowShouldExist(triageCard, "Kebab Triage E2E")

    // Pick a category from the row's combobox — the row leaves the backlog, so it leaves the card.
    pickCategory(triageCard.findElement(By.xpath(".//tr[contains(.,'Kebab Triage E2E')]")), "Triage Food E2E")

    // Asserted against the whole page, not the card: with the backlog empty the card removes itself entirely.
    textShouldDisappear(pageBody, "Kebab Triage E2E")
    categoryShouldBe(tx.id, categoryId)
  }

  it should "seed a categorization rule from a row" in {
    ensurePeriodExists()
    val categoryId = TransactionSeed.addCategory("Triage Fuel E2E")
    val tx         = TransactionSeed.addTransaction("Orlen Triage E2E", -18000)

    openDashboard()
    click(triageCard.findElement(By.xpath(".//tr[contains(.,'Orlen Triage E2E')]")), "+ rule")

    // The modal opens seeded from the row; give it a category and save — the rule categorizes the transaction it came from.
    textShouldAppear(ruleModal, "New rule")
    pickCategory(ruleModal, "Triage Fuel E2E")
    click(ruleModal, "Save rule")

    categoryShouldBe(tx.id, categoryId)
    textShouldDisappear(pageBody, "Orlen Triage E2E")
  }

  it should "link out to the full transactions list" in {
    ensurePeriodExists()
    TransactionSeed.addTransaction("Linkout Triage E2E", -1200)

    openDashboard()
    textShouldAppear(triageCard, "Linkout Triage E2E")
    triageCard.findElement(By.xpath(".//a[contains(.,'all transactions')]")).click()
    waitForPage("Transactions")
  }
}
