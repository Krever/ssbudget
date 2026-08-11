package ssbudget.e2e

import org.openqa.selenium.By

import scala.jdk.CollectionConverters.*

/** Inspecting the rule that categorized a transaction, from the transaction row.
  *
  * The rule isn't recorded on the transaction: the rule engine re-resolves every non-manual row against the whole rule set on any rule change and
  * after every import, so the first matching rule by priority IS the one that assigned the category. These cover that the row surfaces it and opens
  * it.
  */
class RuleInspectionSpec extends E2ESpec {

  // The badge, distinguished from the "+ rule" button next to it.
  private val ruleBadge = By.xpath(".//button[contains(., 'rule')][not(contains(., '+'))]")

  /** Seed a rule-categorized transaction and open the list filtered to its category, so the row is on screen. */
  private def seedRuleCategorized(counterparty: String, ruleName: String, categoryName: String): Unit = {
    val categoryId = TransactionSeed.addCategory(categoryName)
    TransactionSeed.addRule(ruleName, categoryId, counterparty)
    TransactionSeed.addTransaction(counterparty, -4200)

    // Let the engine assign the category, exactly as it does after a real import.
    driver.get(s"$baseUrl/transactions")
    waitForPage("Transactions")
    click(findCard("Categorization rules"), "Re-apply")
    Thread.sleep(500)

    driver.get(s"$baseUrl/transactions?category=all")
    waitForPage("Transactions")
  }

  "A rule-categorized transaction" should "show a rule badge naming the rule that set it" in {
    ensurePeriodExists()
    seedRuleCategorized("Zabka E2E", "Zabka rule", "Convenience E2E")

    val row   = txTable.findElement(By.xpath(".//tr[.//div[contains(text(),'Zabka E2E')]]"))
    val badge = row.findElement(ruleBadge)

    badge.getAttribute("title") should include("Zabka rule")
  }

  it should "open that rule for inspection when the badge is clicked" in {
    ensurePeriodExists()
    seedRuleCategorized("Orlen E2E", "Fuel rule", "Fuel E2E")

    val row = txTable.findElement(By.xpath(".//tr[.//div[contains(text(),'Orlen E2E')]]"))
    row.findElement(ruleBadge).click()
    Thread.sleep(500)

    // The rules modal, populated with the rule behind this transaction rather than a blank new-rule form.
    val modal = driver.findElement(By.cssSelector(".modal.show, .modal.d-block"))
    modal.getText should include("Edit rule")

    // Its name and the condition it matches on are both loaded (criteria render as editable inputs, so they're values not text).
    val values = modal.findElements(By.cssSelector("input")).asScala.toList.map(_.getAttribute("value"))
    values should contain("Fuel rule")
    values should contain("Orlen E2E")

    modal.getText should include("Matches") // the live preview of what the rule covers
  }

  "A manually categorized transaction" should "not show a rule badge" in {
    ensurePeriodExists()
    val categoryId = TransactionSeed.addCategory("Manual E2E")
    TransactionSeed.addTransaction(
      "Manual Payee E2E",
      -1500,
      categoryId = Some(categoryId),
      categorySource = Some(ssbudget.shared.model.CategorySource.Manual),
    )

    driver.get(s"$baseUrl/transactions?category=all")
    waitForPage("Transactions")

    val row = txTable.findElement(By.xpath(".//tr[.//div[contains(text(),'Manual Payee E2E')]]"))
    assertAbsent(row, ruleBadge)
  }
}
