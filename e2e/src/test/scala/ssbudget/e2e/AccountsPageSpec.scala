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

  "Accounts page" should "show a group for each account type" in {
    open()
    List("spending" -> "Spending", "holding" -> "On hold", "savings" -> "Savings", "investment" -> "Investments").foreach { case (key, label) =>
      accountsGroup(key).getText should include(label)
    }
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

    // The account now stands after the heading of "On hold" and before the heading of "Savings".
    eventually {
      val names = rows(accountsCard).map(_.getText)
      val at    = names.indexWhere(_.contains("Becomes On Hold"))
      at should be > names.indexWhere(_.startsWith("On hold"))
      at should be < names.indexWhere(_.startsWith("Savings"))
    }
  }

  // ============ Savings accounts ============

  it should "add a new savings account" in {
    open()
    val initialCount = rows(accountsCard).size
    click(accountsGroup("savings"), "+ Add")

    accountAddRow.findElement(By.cssSelector("input[type='text']")).sendKeys("New Savings")
    click(accountAddRow, "Add")

    eventually(rows(accountsCard).size shouldBe (initialCount + 1))
    // The row for a new account starts with the type of the group whose "+ Add" opened it.
    eventually {
      val names = rows(accountsCard).map(_.getText)
      names.indexWhere(_.contains("New Savings")) should be > names.indexWhere(_.startsWith("Savings"))
    }
  }

  it should "cancel adding savings account" in {
    open()
    val initialCount = rows(accountsCard).size
    click(accountsGroup("savings"), "+ Add")
    eventually(rows(accountsCard).size shouldBe (initialCount + 1))
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
