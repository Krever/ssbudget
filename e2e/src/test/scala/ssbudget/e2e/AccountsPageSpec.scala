package ssbudget.e2e

import org.openqa.selenium.By

/** The Accounts page: one ledger with a heading row for each account type, as the Accounts card of the Dashboard has. */
class AccountsPageSpec extends E2ESpec {

  private def open(): Unit = {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")
  }

  private def rowOf(name: String) = accountsCard.findElement(By.xpath(s".//tr[.//td[contains(text(),'$name')]]"))

  private def editRow = accountsCard.findElement(By.cssSelector("tbody tr.table-warning"))

  "Accounts page" should "show a group only for a type that has an account, and the spending group always" in {
    addSavingsAccount("Group Test Savings")
    open()
    accountsGroup("spending").getText should include("Spending")
    accountsGroup("savings").getText should include("Savings")
    // No account is on hold: that type has no heading row.
    withoutImplicitWait(accountsCard.findElements(By.cssSelector("tr[data-group='holding']")).size()) shouldBe 0
  }

  // ============ Spending accounts ============

  it should "add a new bank account" in {
    open()
    val initialCount = rows(accountsCard).size
    click(accountsGroup("spending"), "+ Add")

    accountAddRow.findElement(By.cssSelector("input[type='text']")).sendKeys("Test Account")
    click(accountAddRow, "Add")

    eventually(rows(accountsCard).size shouldBe (initialCount + 1))
    rowShouldExist(accountsCard, "Test Account")
  }

  it should "cancel adding bank account" in {
    open()
    val initialCount = rows(accountsCard).size
    click(accountsGroup("spending"), "+ Add")
    eventually(rows(accountsCard).size shouldBe (initialCount + 1))
    click(accountAddRow, "Cancel")

    eventually(rows(accountsCard).size shouldBe initialCount)
  }

  it should "enter and cancel edit mode for bank account" in {
    addBankAccount("Edit Test Account")
    open()

    click(rowOf("Edit Test Account"), "Edit")
    editRow.isDisplayed shouldBe true
    editRow.findElement(By.cssSelector("input[type='text']")).getAttribute("value") shouldBe "Edit Test Account"

    click(editRow, "Cancel")
    rowShouldExist(accountsCard, "Edit Test Account")
  }

  it should "show the total of a group in its heading row" in {
    addBankAccount("Footer Test Account")
    open()

    // Every balance is zero here; the heading still carries the total, in the primary currency.
    eventually(accountsGroup("spending").getText should include("PLN"))
  }

  it should "move an account to the group of its new type" in {
    addBankAccount("Becomes On Hold")
    open()

    click(rowOf("Becomes On Hold"), "Edit")
    editRow.findElement(By.cssSelector("select[data-field='type'] option[value='holding']")).click()
    click(editRow, "Save")

    // The heading of "On hold" is there now, and the account is the row after it.
    eventually {
      val names   = rows(accountsCard).map(_.getText)
      val heading = names.indexWhere(_.startsWith("On hold"))
      heading should be >= 0
      names.indexWhere(_.contains("Becomes On Hold")) shouldBe heading + 1
    }
  }

  // ============ Savings accounts ============

  it should "add a new savings account" in {
    open()
    openAccountAddRow("savings")

    accountAddRow.findElement(By.cssSelector("input[type='text']")).sendKeys("New Savings")
    click(accountAddRow, "Add")

    // The account is in the savings group, whatever group the row was opened from.
    eventually {
      val names   = rows(accountsCard).map(_.getText)
      val heading = names.indexWhere(_.startsWith("Savings"))
      heading should be >= 0
      names.indexWhere(_.contains("New Savings")) should be > heading
    }
  }

  it should "cancel adding savings account" in {
    open()
    val initialCount = rows(accountsCard).size
    openAccountAddRow("savings")
    click(accountAddRow, "Cancel")

    eventually(rows(accountsCard).size shouldBe initialCount)
  }

  it should "rename a savings account" in {
    addSavingsAccount("Edit Savings Test")
    open()

    click(rowOf("Edit Savings Test"), "Edit")
    val nameInput = editRow.findElement(By.cssSelector("input[type='text']"))
    nameInput.clear()
    nameInput.sendKeys("Renamed Savings")
    click(editRow, "Save")

    textShouldAppear(accountsCard, "Renamed Savings")
  }

  it should "delete savings account" in {
    addSavingsAccount("To Delete")
    open()
    val countBefore = rows(accountsCard).size

    click(rowOf("To Delete"), "Edit")
    click(editRow, "Del")
    // A delete takes two clicks: the first one arms the button.
    click(editRow, "Sure?")

    eventually(rows(accountsCard).size shouldBe (countBefore - 1))
    rowShouldNotExist(accountsCard, "To Delete")
  }
}
