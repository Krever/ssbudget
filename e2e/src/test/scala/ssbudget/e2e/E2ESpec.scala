package ssbudget.e2e

import io.github.bonigarcia.wdm.WebDriverManager
import org.openqa.selenium.chrome.{ChromeDriver, ChromeOptions}
import org.openqa.selenium.support.ui.{ExpectedConditions, WebDriverWait}
import org.openqa.selenium.{By, JavascriptExecutor, WebDriver, WebElement}
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import java.time.Duration
import scala.jdk.CollectionConverters.*

/** One headless Chrome for the whole run.
  *
  * Launching a browser costs a second or two, which dwarfs most of these tests, so the driver is created once and every spec borrows it. Per-test
  * isolation comes from clearing browser state in `beforeEach` instead of from a fresh process. Specs needing different Chrome options (a download
  * directory, a virtual authenticator) still build their own driver and are responsible for quitting it.
  */
object SharedDriver {

  lazy val instance: WebDriver = {
    WebDriverManager.chromedriver().setup()
    val options = new ChromeOptions()
    options.addArguments("--headless", "--no-sandbox", "--disable-dev-shm-usage")
    val driver  = new ChromeDriver(options)
    driver.manage().timeouts().implicitlyWait(E2ESpec.implicitWait)
    Runtime.getRuntime.addShutdownHook(new Thread(() => driver.quit()))
    driver
  }
}

object E2ESpec {

  /** How long element lookups keep retrying. Also the ceiling for [[Eventually]] assertions. */
  val implicitWait: Duration = Duration.ofSeconds(10)

  /** Locates one entry of the Dashboard's Plan by name: the block holding its bar, state line and buttons. Lives here because `DemoScenarioSpec`
    * builds its own driver and can't extend the trait, and one xpath with two owners would drift.
    */
  def planEntryXpath(name: String): String =
    s"//span[contains(@class,'fw-semibold') and contains(.,'$name')]/ancestor::div[contains(@class,'mb-3')][1]"
}

trait E2ESpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach with Eventually {

  import scala.compiletime.uninitialized
  protected var driver: WebDriver = uninitialized

  /** Poll rather than sleep: assertions wrapped in `eventually` pass as soon as the UI catches up, so the common case costs one interval instead of a
    * fixed guess. Re-find elements INSIDE the block — a reference captured outside goes stale when the DOM updates and would never recover.
    */
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(E2ESpec.implicitWait.toSeconds, Seconds), interval = Span(100, Millis))

  // If E2E_BASE_URL is set, use it (external servers). Otherwise the first spec to run starts them.
  protected def baseUrl: String =
    sys.env.getOrElse("E2E_BASE_URL", TestServers.frontendUrl)

  override def beforeAll(): Unit = {
    // `startAll` is idempotent, so every spec can ask and only the first one pays: the backend, vite and the temp database are shared by the whole run
    // and torn down by TestServers' shutdown hook. There is deliberately no aggregate Suite — one would make sbt run every spec twice, once on its own
    // and once nested.
    if sys.env.get("E2E_BASE_URL").isEmpty then {
      TestServers.startAll()
    }
  }

  override def beforeEach(): Unit = {
    driver = SharedDriver.instance
    resetBrowserState()
  }

  override def afterEach(): Unit = () // the shared driver outlives the spec; a shutdown hook quits it

  /** Undo anything a previous test left in the browser. The database is deliberately NOT reset — these specs have always shared it — but persisted UI
    * preferences would otherwise leak between tests.
    */
  private def resetBrowserState(): Unit = {
    // localStorage is per-origin, so we must be on the app before we can clear it. After the first test we already are.
    if !driver.getCurrentUrl.startsWith(baseUrl) then driver.get(baseUrl)
    driver.asInstanceOf[JavascriptExecutor].executeScript("window.localStorage.clear(); window.sessionStorage.clear();")
  }

  protected def waitFor: WebDriverWait = new WebDriverWait(driver, E2ESpec.implicitWait)

  protected def waitForPage(title: String): Unit =
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h4"), title))

  protected def findCard(headerText: String): WebElement =
    driver.findElement(By.xpath(s"//span[text()='$headerText']/ancestor::div[contains(@class,'card')]"))

  protected def findCardByDiv(headerText: String): WebElement =
    driver.findElement(By.xpath(s"//div[text()='$headerText']/ancestor::div[contains(@class,'card')]"))

  protected def findCardByH5(headerText: String): WebElement =
    driver.findElement(By.xpath(s"//h5[text()='$headerText']/ancestor::div[contains(@class,'card')]"))

  protected def rows(parent: WebElement): List[WebElement] =
    parent.findElements(By.cssSelector("tbody tr")).asScala.toList

  /** The transaction table on the Transactions page. Scoped by a header only it has — the categories card's table shares its CSS classes and comes
    * first in the DOM.
    */
  protected def txTable: WebElement =
    driver.findElement(By.xpath("//table[.//th[text()='Description']]"))

  /** Visible text of every selected `<option>` under `parent` — for asserting which filters a page landed on. */
  protected def selectedOptionTexts(parent: WebElement): List[String] =
    parent.findElements(By.cssSelector("option")).asScala.toList.filter(_.isSelected).map(_.getText)

  /** Assert nothing under `parent` matches `locator`.
    *
    * Do NOT write this as a bare `findElements(...) shouldBe empty`: with an implicit wait set, Selenium keeps retrying until the timeout before
    * conceding that a set is empty, so every negative assertion would cost the full 10 seconds.
    */
  protected def assertAbsent(parent: WebElement, locator: By): Unit =
    withoutImplicitWait(parent.findElements(locator).asScala shouldBe empty)

  private def withoutImplicitWait[A](body: => A): A = {
    driver.manage().timeouts().implicitlyWait(Duration.ZERO)
    try body
    finally driver.manage().timeouts().implicitlyWait(E2ESpec.implicitWait)
  }

  // ---- Polling assertions ----
  // The UI updates asynchronously after an action, so these READ BY POLLING. `parent` is by-name on purpose: a Laminar re-render replaces the rows it
  // returns, so anything captured before the action would go stale mid-assertion. Never assert on a snapshot taken before a click.

  protected def textShouldAppear(parent: => WebElement, text: String): Unit =
    eventually(parent.getText should include(text))

  protected def textShouldDisappear(parent: => WebElement, text: String): Unit =
    eventually(parent.getText should not include text)

  protected def rowShouldExist(parent: => WebElement, text: String): Unit =
    eventually(rows(parent).exists(_.getText.contains(text)) shouldBe true)

  protected def rowShouldNotExist(parent: => WebElement, text: String): Unit =
    eventually(rows(parent).exists(_.getText.contains(text)) shouldBe false)

  protected def click(parent: WebElement, buttonText: String): Unit = {
    // Use . instead of text() to match text in child elements (like spans)
    parent.findElement(By.xpath(s".//button[contains(.,'$buttonText')]")).click()
  }

  protected def clickIfExists(parent: WebElement, buttonText: String): Boolean = {
    // Use . instead of text() to match text in child elements (like spans)
    val buttons = parent.findElements(By.xpath(s".//button[contains(.,'$buttonText')]")).asScala
    if buttons.nonEmpty then {
      buttons.head.click()
      true
    } else false
  }

  // ============ Setup Helpers ============

  /** Open the Dashboard — the app's single working surface, home to the summary, accounts, planned items and category budgets. */
  protected def openDashboard(): Unit = {
    driver.get(baseUrl)
    waitForPage("Dashboard")
  }

  /** Ensure there's a current period, starting one if needed */
  protected def ensurePeriodExists(): Unit = {
    driver.get(s"$baseUrl/periods")
    waitForPage("Periods")

    val currentPeriodCard = findCardByDiv("Current Period")
    // Check if "No active period" message is shown - only then click button
    val cardText          = currentPeriodCard.getText
    if cardText.contains("No active period") then {
      click(currentPeriodCard, "Start New Period")
      eventually(findCardByDiv("Current Period").getText should not include "No active period")
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
    eventually(findCard("Bank Accounts").getText should include(name))
  }

  /** Add a savings account */
  protected def addSavingsAccount(name: String): Unit = {
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")

    val savingsCard = findCard("Savings Accounts")
    click(savingsCard, "+ Add")

    val addRow = savingsCard.findElement(By.cssSelector("tbody tr.table-success"))
    addRow.findElement(By.cssSelector("input[type='text']")).sendKeys(name)
    click(addRow, "Add")
    eventually(findCard("Savings Accounts").getText should include(name))
  }

  /** One entry of the Dashboard's Plan, found by name: the block holding its bar, state line and buttons. Re-found on each call, because Laminar
    * replaces the block on every update and a captured reference would go stale.
    */
  protected def planEntry(name: String): WebElement =
    driver.findElement(By.xpath(E2ESpec.planEntryXpath(name)))

  /** The name line of a Plan entry — for a category entry that's also the drill-down toggle. */
  protected def planEntryName(name: String): WebElement =
    driver.findElement(By.xpath(s"//span[contains(@class,'fw-semibold') and contains(.,'$name')]"))

  /** Turn off the Plan's "Hide done" switch, which otherwise hides settled entries and covered budgets. Idempotent. */
  protected def showDonePlanEntries(): Unit = {
    val toggle = driver.findElement(By.id("hideDone"))
    if toggle.isSelected then {
      toggle.click()
      eventually(driver.findElement(By.id("hideDone")).isSelected shouldBe false)
    }
  }

  /** Add a manual entry to the Dashboard's Plan — an expense or an income, per `income`. */
  private def addPlannedItem(name: String, amount: Double, income: Boolean): Unit = {
    openDashboard()

    val card = findCard("Plan")
    click(card, if income then "+ Income" else "+ Expense")

    val form = driver.findElement(By.id(if income then "plan-add-income" else "plan-add-expense"))
    form.findElement(By.cssSelector("input[type='text']")).sendKeys(name)
    form.findElement(By.cssSelector("input[type='number']")).sendKeys(amount.toString)
    click(form, "Add")
    eventually(findCard("Plan").getText should include(name))
  }

  protected def addPlannedExpense(name: String, amount: Double): Unit = addPlannedItem(name, amount, income = false)

  protected def addPlannedIncome(name: String, amount: Double): Unit = addPlannedItem(name, amount, income = true)

}
