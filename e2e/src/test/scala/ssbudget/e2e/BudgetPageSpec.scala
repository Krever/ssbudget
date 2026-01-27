package ssbudget.e2e

import org.openqa.selenium.By
import scala.jdk.CollectionConverters.*

class BudgetPageSpec extends E2ESpec {

  "Budget page" should "load planned items and estimated expenses" in {
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val cardTexts = driver.findElements(By.cssSelector(".card")).asScala.map(_.getText).toList
    cardTexts.exists(_.contains("Planned Items")) shouldBe true
    cardTexts.exists(_.contains("Estimated Expenses")) shouldBe true
  }

  it should "add a new planned expense" in {
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card = findCard("Planned Items")
    click(card, "+ Expense")

    val addRow = card.findElement(By.cssSelector("tr.table-primary"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys("Test Expense")
    addRow.findElement(By.cssSelector("input[type='number']")).sendKeys("123.45")
    click(addRow, "Add")

    rows(card).exists(_.getText.contains("Test Expense")) shouldBe true
  }

  it should "add a new planned income" in {
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card = findCard("Planned Items")
    click(card, "+ Income")

    val addRow = card.findElement(By.cssSelector("tr.table-primary"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys("Test Income")
    addRow.findElement(By.cssSelector("input[type='number']")).sendKeys("500")
    click(addRow, "Add")

    rows(card).exists(_.getText.contains("Test Income")) shouldBe true
  }

  it should "pay expense with default amount" in {
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card        = findCard("Planned Items")
    val pendingRows = card.findElements(By.xpath(".//tr[.//span[contains(text(),'Pending')]]")).asScala.toList

    if pendingRows.nonEmpty then {
      click(pendingRows.head, "Pay")
      click(card.findElement(By.cssSelector("tr.table-info")), "Save")
      rows(card).count(_.getText.contains("Paid")) should be >= 1
    }
  }

  it should "pay expense with overridden amount" in {
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card        = findCard("Planned Items")
    val pendingRows = card.findElements(By.xpath(".//tr[.//span[contains(text(),'Pending')]]")).asScala.toList

    if pendingRows.nonEmpty then {
      click(pendingRows.head, "Pay")
      val editRow = card.findElement(By.cssSelector("tr.table-info"))
      val input   = editRow.findElement(By.cssSelector("input[type='number']"))
      input.clear()
      input.sendKeys("99.99")
      click(editRow, "Save")

      rows(card).exists(_.getText.contains("99.99")) shouldBe true
    }
  }

  it should "edit and delete a budget item" in {
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card = findCard("Estimated Expenses")
    click(card, "+ Add")

    val addRow = card.findElement(By.cssSelector("tr.table-primary"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys("To Delete")
    addRow.findElement(By.cssSelector("input[type='number']")).sendKeys("100")
    click(addRow, "Add")

    val toDelete = card.findElement(By.xpath(".//tr[.//td[contains(text(),'To Delete')]]"))
    click(toDelete, "Edit")
    click(card.findElement(By.cssSelector("tr.table-warning")), "Del")

    rows(card).exists(_.getText.contains("To Delete")) shouldBe false
  }
}
