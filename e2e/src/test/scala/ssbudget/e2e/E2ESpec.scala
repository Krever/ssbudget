package ssbudget.e2e

import io.github.bonigarcia.wdm.WebDriverManager
import org.openqa.selenium.{By, WebDriver, WebElement}
import org.openqa.selenium.chrome.{ChromeDriver, ChromeOptions}
import org.openqa.selenium.support.ui.{ExpectedConditions, WebDriverWait}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Duration
import scala.jdk.CollectionConverters.*

trait E2ESpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  import scala.compiletime.uninitialized
  protected var driver: WebDriver = uninitialized

  // If E2E_BASE_URL is set, use it (external servers)
  // Otherwise, TestServers will be started by E2ESuite
  protected def baseUrl: String =
    sys.env.getOrElse("E2E_BASE_URL", TestServers.frontendUrl)

  override def beforeAll(): Unit = {
    // Start servers if not using external servers and not already started
    if sys.env.get("E2E_BASE_URL").isEmpty then {
      TestServers.startAll()
    }
    WebDriverManager.chromedriver().setup()
  }

  override def afterAll(): Unit = {
    // Servers are stopped by E2ESuite if running via suite,
    // or need to be stopped here if running individual spec
    // TestServers.stopAll() is idempotent
  }

  override def beforeEach(): Unit = {
    val options = new ChromeOptions()
    options.addArguments("--headless", "--no-sandbox", "--disable-dev-shm-usage")
    driver = new ChromeDriver(options)
    driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10))
  }

  override def afterEach(): Unit = if driver != null then driver.quit()

  protected def waitFor: WebDriverWait = new WebDriverWait(driver, Duration.ofSeconds(10))

  protected def waitForPage(title: String): Unit = {
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h4"), title))
    Thread.sleep(300)
  }

  protected def findCard(headerText: String): WebElement =
    driver.findElement(By.xpath(s"//span[text()='$headerText']/ancestor::div[contains(@class,'card')]"))

  protected def findCardByDiv(headerText: String): WebElement =
    driver.findElement(By.xpath(s"//div[text()='$headerText']/ancestor::div[contains(@class,'card')]"))

  protected def rows(parent: WebElement): List[WebElement] =
    parent.findElements(By.cssSelector("tbody tr")).asScala.toList

  protected def click(parent: WebElement, buttonText: String): Unit = {
    // Use . instead of text() to match text in child elements (like spans)
    parent.findElement(By.xpath(s".//button[contains(.,'$buttonText')]")).click()
    Thread.sleep(300)
  }

  protected def clickIfExists(parent: WebElement, buttonText: String): Boolean = {
    // Use . instead of text() to match text in child elements (like spans)
    val buttons = parent.findElements(By.xpath(s".//button[contains(.,'$buttonText')]")).asScala
    if buttons.nonEmpty then {
      buttons.head.click()
      Thread.sleep(300)
      true
    } else false
  }

  // ============ Setup Helpers ============

  /** Ensure there's a current period, starting one if needed */
  protected def ensurePeriodExists(): Unit = {
    driver.get(s"$baseUrl/periods")
    waitForPage("Periods")

    val currentPeriodCard = findCardByDiv("Current Period")
    // Check if "No active period" message is shown - only then click button
    val cardText          = currentPeriodCard.getText
    if cardText.contains("No active period") then {
      click(currentPeriodCard, "Start New Period")
      Thread.sleep(500)
      // Refresh to see updated state
      driver.get(s"$baseUrl/periods")
      waitForPage("Periods")
    }
    // Otherwise period already exists, nothing to do
  }

  /** Add a bank account and return to the specified page */
  protected def addBankAccount(name: String, currency: String = "PLN"): Unit = {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val bankCard = findCard("Bank Accounts")
    click(bankCard, "+ Add")

    val addRow = bankCard.findElement(By.cssSelector("tbody tr.table-primary"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys(name)
    // Select currency if not PLN
    if currency != "PLN" then {
      val select = addRow.findElement(By.cssSelector("select"))
      select.findElement(By.xpath(s".//option[text()='$currency']")).click()
    }
    click(addRow, "Add")
    Thread.sleep(300)
  }

  /** Add a savings account */
  protected def addSavingsAccount(name: String, targetAmount: Option[Int] = None): Unit = {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val savingsCard = findCard("Savings Accounts")
    click(savingsCard, "+ Add")

    val addRow = savingsCard.findElement(By.cssSelector("tbody tr.table-success"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys(name)
    targetAmount.foreach { amount =>
      addRow.findElement(By.cssSelector("input[type='number']")).sendKeys(amount.toString)
    }
    click(addRow, "Add")
    Thread.sleep(300)
  }

  /** Add a planned expense */
  protected def addPlannedExpense(name: String, amount: Double): Unit = {
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card = findCard("Planned Items")
    click(card, "+ Expense")

    val addRow = card.findElement(By.cssSelector("tr.table-primary"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys(name)
    addRow.findElement(By.cssSelector("input[type='number']")).sendKeys(amount.toString)
    click(addRow, "Add")
    Thread.sleep(300)
  }

  /** Add a planned income */
  protected def addPlannedIncome(name: String, amount: Double): Unit = {
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card = findCard("Planned Items")
    click(card, "+ Income")

    val addRow = card.findElement(By.cssSelector("tr.table-primary"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys(name)
    addRow.findElement(By.cssSelector("input[type='number']")).sendKeys(amount.toString)
    click(addRow, "Add")
    Thread.sleep(300)
  }

  /** Add an estimated expense */
  protected def addEstimatedExpense(name: String, amount: Double): Unit = {
    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")

    val card = findCard("Estimated Expenses")
    click(card, "+ Add")

    val addRow = card.findElement(By.cssSelector("tr.table-primary"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys(name)
    addRow.findElement(By.cssSelector("input[type='number']")).sendKeys(amount.toString)
    click(addRow, "Add")
    Thread.sleep(300)
  }
}
