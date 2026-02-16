package ssbudget.e2e

import org.openqa.selenium.By
import scala.jdk.CollectionConverters.*

class OneTimeExpensesSpec extends E2ESpec {

  "One-time expenses" should "add, edit, and delete via budget page and view on history page" in {
    ensurePeriodExists()

    // === Navigate to Budget page ===
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    findCard("One-Time Expenses").getText should include("One-Time Expenses")

    // === Add a one-time expense ===
    click(findCard("One-Time Expenses"), "+ Add")
    val addRow = findCard("One-Time Expenses").findElement(By.cssSelector("tr.table-primary"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys("New Laptop")
    addRow.findElement(By.cssSelector("input[type='number']")).sendKeys("4500")
    click(addRow, "Add")

    // Re-find card after DOM update
    findCard("One-Time Expenses").getText should include("New Laptop")
    findCard("One-Time Expenses").getText should include("4,500.00")

    // === Add a second expense ===
    click(findCard("One-Time Expenses"), "+ Add")
    val addRow2 = findCard("One-Time Expenses").findElement(By.cssSelector("tr.table-primary"))
    addRow2.findElement(By.cssSelector("input[type='text']")).sendKeys("Car Repair")
    addRow2.findElement(By.cssSelector("input[type='number']")).sendKeys("1200")
    click(addRow2, "Add")

    findCard("One-Time Expenses").getText should include("Car Repair")

    // === Edit the first expense ===
    val laptopRow = findCard("One-Time Expenses").findElement(By.xpath(".//tr[.//td[contains(text(),'New Laptop')]]"))
    click(laptopRow, "Edit")

    val editRow     = findCard("One-Time Expenses").findElement(By.cssSelector("tr.table-warning"))
    val nameInput   = editRow.findElement(By.cssSelector("input[type='text']"))
    nameInput.clear()
    nameInput.sendKeys("Gaming Laptop")
    val amountInput = editRow.findElement(By.cssSelector("input[type='number']"))
    amountInput.clear()
    amountInput.sendKeys("5500")
    click(editRow, "Save")

    findCard("One-Time Expenses").getText should include("Gaming Laptop")
    findCard("One-Time Expenses").getText should include("5,500.00")
    findCard("One-Time Expenses").getText should not include "New Laptop"

    // === Navigate to the history page via "View All" link ===
    findCard("One-Time Expenses").findElement(By.xpath(".//a[contains(text(),'View All')]")).click()
    waitForPage("One-Time Expenses")

    val historyCard = findCard("All One-Time Expenses")
    historyCard.getText should include("Gaming Laptop")
    historyCard.getText should include("Car Repair")

    // === Delete from history page ===
    val carRow = findCard("All One-Time Expenses").findElement(By.xpath(".//tr[.//td[contains(text(),'Car Repair')]]"))
    click(carRow, "Del")

    findCard("All One-Time Expenses").getText should not include "Car Repair"
    findCard("All One-Time Expenses").getText should include("Gaming Laptop")

    // === Go back to budget page and verify deletion is reflected ===
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    findCard("One-Time Expenses").getText should include("Gaming Laptop")
    findCard("One-Time Expenses").getText should not include "Car Repair"
  }
}
