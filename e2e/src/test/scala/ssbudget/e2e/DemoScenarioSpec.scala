package ssbudget.e2e

import io.github.bonigarcia.wdm.WebDriverManager
import org.openqa.selenium.{By, JavascriptExecutor, WebDriver, WebElement}
import org.openqa.selenium.chrome.{ChromeDriver, ChromeOptions}
import org.openqa.selenium.interactions.Actions
import org.openqa.selenium.support.ui.{ExpectedConditions, WebDriverWait}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Duration
import scala.jdk.CollectionConverters.*

/** Comprehensive demo scenario for recording.
  *
  * This test walks through all major features in a logical order, simulating a realistic user workflow. Designed for
  * screen recording with:
  *   - Visual click indicators (ripple effect)
  *   - Phase banners showing current section
  *   - Authentication flow
  *   - Deliberate pacing between actions
  *
  * Run with: sbt "e2e/testOnly ssbudget.e2e.DemoScenarioSpec"
  *
  * For headed mode (visible browser): E2E_HEADED=true sbt "e2e/testOnly ssbudget.e2e.DemoScenarioSpec"
  */
class DemoScenarioSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  import scala.compiletime.uninitialized
  protected var driver: WebDriver = uninitialized
  private var js: JavascriptExecutor = uninitialized

  protected def baseUrl: String = AuthTestServers.frontendUrl

  // Timing constants for demo (adjust for speed)
  private val shortPause = 500
  private val mediumPause = 900
  private val longPause = 1500
  private val phasePause = 2000

  override def beforeAll(): Unit = {
    AuthTestServers.startAll()
    WebDriverManager.chromedriver().setup()
  }

  override def afterAll(): Unit = {
    AuthTestServers.stopAll()
  }

  // Track if using remote browser (don't quit it after test)
  private var isRemoteBrowser = false

  override def beforeEach(): Unit = {
    val options = new ChromeOptions()

    if (sys.env.get("E2E_REMOTE_BROWSER").isDefined) {
      // Connect to existing browser with remote debugging
      // Start Chrome with: chrome --remote-debugging-port=9222
      val debugPort = sys.env.getOrElse("E2E_DEBUG_PORT", "9222")
      options.setExperimentalOption("debuggerAddress", s"localhost:$debugPort")
      isRemoteBrowser = true
      println(s"[Demo] Connecting to existing browser on port $debugPort...")
    } else {
      // Launch new browser
      if (sys.env.get("E2E_HEADED").isEmpty) {
        options.addArguments("--headless")
      }
      options.addArguments("--no-sandbox", "--disable-dev-shm-usage")
      options.addArguments("--window-size=1400,900")
      isRemoteBrowser = false
    }

    driver = new ChromeDriver(options)
    driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10))
    js = driver.asInstanceOf[JavascriptExecutor]

    if (isRemoteBrowser) {
      // Set window size for remote browser
      driver.manage().window().setSize(new org.openqa.selenium.Dimension(1400, 900))
    }
  }

  override def afterEach(): Unit = {
    if (driver != null && !isRemoteBrowser) {
      driver.quit()
    }
    // Don't quit remote browser - user wants to keep it open
  }

  protected def waitFor: WebDriverWait = new WebDriverWait(driver, Duration.ofSeconds(10))

  // ============ Visual Helpers ============

  /** Inject CSS for click indicator and phase banner */
  private def injectDemoStyles(): Unit = {
    js.executeScript("""
      if (!document.getElementById('demo-styles')) {
        const style = document.createElement('style');
        style.id = 'demo-styles';
        style.textContent = `
          .demo-click-indicator {
            position: fixed;
            width: 40px;
            height: 40px;
            border-radius: 50%;
            background: rgba(255, 87, 34, 0.6);
            border: 3px solid #ff5722;
            pointer-events: none;
            z-index: 99999;
            transform: translate(-50%, -50%) scale(0);
            animation: demo-click-ripple 0.4s ease-out forwards;
          }
          @keyframes demo-click-ripple {
            0% { transform: translate(-50%, -50%) scale(0); opacity: 1; }
            50% { transform: translate(-50%, -50%) scale(1.5); opacity: 0.7; }
            100% { transform: translate(-50%, -50%) scale(2); opacity: 0; }
          }
          .demo-phase-banner {
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            padding: 20px 40px;
            font-size: 28px;
            font-weight: 600;
            text-align: center;
            z-index: 99998;
            box-shadow: 0 4px 20px rgba(0,0,0,0.3);
            animation: demo-phase-slide 0.3s ease-out;
          }
          @keyframes demo-phase-slide {
            from { transform: translateY(-100%); }
            to { transform: translateY(0); }
          }
          .demo-phase-banner.hiding {
            animation: demo-phase-slide-out 0.3s ease-in forwards;
          }
          @keyframes demo-phase-slide-out {
            from { transform: translateY(0); }
            to { transform: translateY(-100%); }
          }
        `;
        document.head.appendChild(style);
      }
    """)
  }

  /** Show a visual indicator where a click happens */
  private def showClickAt(x: Int, y: Int): Unit = {
    js.executeScript(
      s"""
      const indicator = document.createElement('div');
      indicator.className = 'demo-click-indicator';
      indicator.style.left = '${x}px';
      indicator.style.top = '${y}px';
      document.body.appendChild(indicator);
      setTimeout(() => indicator.remove(), 400);
    """
    )
  }

  /** Show phase banner with title */
  private def showPhase(title: String): Unit = {
    js.executeScript(
      s"""
      // Remove existing banner
      const existing = document.querySelector('.demo-phase-banner');
      if (existing) existing.remove();

      const banner = document.createElement('div');
      banner.className = 'demo-phase-banner';
      banner.textContent = '$title';
      document.body.appendChild(banner);
    """
    )
    Thread.sleep(phasePause)
    js.executeScript("""
      const banner = document.querySelector('.demo-phase-banner');
      if (banner) {
        banner.classList.add('hiding');
        setTimeout(() => banner.remove(), 300);
      }
    """)
    Thread.sleep(350)
  }

  /** Click with visual indicator */
  private def demoClick(element: WebElement): Unit = {
    val rect = element.getRect
    val x = rect.getX + rect.getWidth / 2
    val y = rect.getY + rect.getHeight / 2
    showClickAt(x.toInt, y.toInt)
    Thread.sleep(150)
    element.click()
    Thread.sleep(shortPause)
  }

  /** Click button by text with visual indicator */
  private def demoClickButton(parent: WebElement, buttonText: String): Unit = {
    val btn = parent.findElement(By.xpath(s".//button[contains(.,'$buttonText')]"))
    demoClick(btn)
  }

  /** Type text slowly for visibility */
  private def typeSlowly(element: WebElement, text: String): Unit = {
    text.foreach { char =>
      element.sendKeys(char.toString)
      Thread.sleep(25)
    }
  }

  private def pause(ms: Int = mediumPause): Unit = Thread.sleep(ms)

  // ============ Page Helpers ============

  private def waitForPage(title: String): Unit = {
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h4"), title))
    Thread.sleep(300)
  }

  private def findCard(headerText: String): WebElement =
    driver.findElement(By.xpath(s"//span[text()='$headerText']/ancestor::div[contains(@class,'card')]"))

  private def findCardByDiv(headerText: String): WebElement =
    driver.findElement(By.xpath(s"//div[text()='$headerText']/ancestor::div[contains(@class,'card')]"))

  private def findCardByH5(headerText: String): WebElement =
    driver.findElement(By.xpath(s"//h5[text()='$headerText']/ancestor::div[contains(@class,'card')]"))

  // ============ Demo Scenario ============

  "Demo Scenario" should "walk through complete budget workflow with auth" in {

    // ========================================
    // PART 0: Authentication - Password Setup
    // ========================================

    driver.get(baseUrl)
    injectDemoStyles()

    // Give time to start recording if using remote browser
    if (isRemoteBrowser) {
      println("[Demo] Page loaded - start your recording now! (3 seconds...)")
      Thread.sleep(3000)
    } else {
      pause()
    }

    showPhase("🔐 Setting Up Authentication")

    // Wait for setup page
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h3"), "SSBudget Setup"))
    pause()

    // Enter password
    val passwordField = driver.findElement(By.id("password"))
    demoClick(passwordField)
    typeSlowly(passwordField, "demo-password-123")
    pause(shortPause)

    val confirmField = driver.findElement(By.id("confirm"))
    demoClick(confirmField)
    typeSlowly(confirmField, "demo-password-123")
    pause()

    // Create password
    val createBtn = driver.findElement(By.xpath("//button[text()='Create Password']"))
    demoClick(createBtn)

    // Wait for dashboard
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h4"), "Dashboard"))
    pause(longPause)

    // Re-inject styles after page change
    injectDemoStyles()

    // ========================================
    // PART 1: Start Period
    // ========================================

    showPhase("📅 Starting a New Period")

    driver.get(s"$baseUrl/periods")
    waitForPage("Periods")
    injectDemoStyles()
    pause()

    val periodCard = findCardByDiv("Current Period")
    if (periodCard.getText.contains("No active period")) {
      demoClickButton(periodCard, "Start New Period")
      pause(longPause)
    }

    // ========================================
    // PART 2: Configure Currencies
    // ========================================

    showPhase("💱 Configuring Currencies")

    driver.get(s"$baseUrl/settings")
    waitFor.until(_ => driver.findElement(By.tagName("h2")).getText.contains("Settings"))
    injectDemoStyles()
    pause()

    val currenciesCard = findCardByH5("Currencies")

    // Add USD currency
    val currencyInput = currenciesCard.findElement(By.cssSelector("input[type='text']"))
    demoClick(currencyInput)
    typeSlowly(currencyInput, "USD")
    pause(shortPause)
    demoClickButton(currenciesCard, "Add Currency")
    pause()

    // Refresh exchange rates
    demoClickButton(currenciesCard, "Refresh Rates")
    pause(longPause)

    waitFor.until { _ =>
      val alerts = driver.findElements(By.cssSelector(".alert-success")).asScala
      alerts.exists(_.getText.contains("Exchange rates refreshed"))
    }
    pause()

    // ========================================
    // PART 3: Set Up Accounts
    // ========================================

    showPhase("🏦 Adding Bank Accounts")

    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")
    injectDemoStyles()
    pause()

    // Add main bank account (PLN)
    val bankCard = findCard("Bank Accounts")
    demoClickButton(bankCard, "+ Add")
    pause(shortPause)

    val bankAddRow = bankCard.findElement(By.cssSelector("tbody tr.table-primary"))
    val bankNameInput = bankAddRow.findElement(By.cssSelector("input[type='text']"))
    demoClick(bankNameInput)
    typeSlowly(bankNameInput, "Main Account")
    pause(shortPause)
    demoClickButton(bankAddRow, "Add")
    pause()

    // Add EUR account
    demoClickButton(bankCard, "+ Add")
    pause(shortPause)

    val eurAddRow = bankCard.findElement(By.cssSelector("tbody tr.table-primary"))
    val eurNameInput = eurAddRow.findElement(By.cssSelector("input[type='text']"))
    demoClick(eurNameInput)
    typeSlowly(eurNameInput, "Euro Savings")
    val eurSelect = eurAddRow.findElement(By.cssSelector("select"))
    demoClick(eurSelect)
    eurSelect.findElement(By.xpath(".//option[text()='EUR']")).click()
    pause(shortPause)
    demoClickButton(eurAddRow, "Add")
    pause()

    showPhase("💰 Adding Savings Accounts")
    injectDemoStyles()

    // Add savings account with target
    val savingsCard = findCard("Savings Accounts")
    demoClickButton(savingsCard, "+ Add")
    pause(shortPause)

    val savingsAddRow = savingsCard.findElement(By.cssSelector("tbody tr.table-success"))
    val savingsNameInput = savingsAddRow.findElement(By.cssSelector("input[type='text']"))
    demoClick(savingsNameInput)
    typeSlowly(savingsNameInput, "Emergency Fund")
    val targetInput = savingsAddRow.findElement(By.cssSelector("input[type='number']"))
    targetInput.clear()
    demoClick(targetInput)
    typeSlowly(targetInput, "500")
    pause(shortPause)
    demoClickButton(savingsAddRow, "Add")
    pause()

    // Add vacation fund
    demoClickButton(savingsCard, "+ Add")
    pause(shortPause)

    val vacationAddRow = savingsCard.findElement(By.cssSelector("tbody tr.table-success"))
    val vacationNameInput = vacationAddRow.findElement(By.cssSelector("input[type='text']"))
    demoClick(vacationNameInput)
    typeSlowly(vacationNameInput, "Vacation Fund")
    val vacationTarget = vacationAddRow.findElement(By.cssSelector("input[type='number']"))
    vacationTarget.clear()
    demoClick(vacationTarget)
    typeSlowly(vacationTarget, "300")
    pause(shortPause)
    demoClickButton(vacationAddRow, "Add")
    pause()

    // ========================================
    // PART 4: Plan Budget
    // ========================================

    showPhase("📋 Planning Income & Expenses")

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")
    injectDemoStyles()
    pause()

    val plannedCard = findCard("Planned Items")

    // Add income
    demoClickButton(plannedCard, "+ Income")
    pause(shortPause)

    val incomeRow = plannedCard.findElement(By.cssSelector("tr.table-primary"))
    val incomeNameInput = incomeRow.findElement(By.cssSelector("input[type='text']"))
    demoClick(incomeNameInput)
    typeSlowly(incomeNameInput, "Monthly Salary")
    val incomeAmountInput = incomeRow.findElement(By.cssSelector("input[type='number']"))
    demoClick(incomeAmountInput)
    typeSlowly(incomeAmountInput, "8500")
    pause(shortPause)
    demoClickButton(incomeRow, "Add")
    pause()

    // Add planned expenses
    val expenses = List(
      ("Rent", "2000"),
      ("Utilities", "350"),
      ("Subscriptions", "85")
    )

    expenses.foreach { case (name, amount) =>
      demoClickButton(findCard("Planned Items"), "+ Expense")
      pause(shortPause)

      val expenseRow = findCard("Planned Items").findElement(By.cssSelector("tr.table-primary"))
      val expNameInput = expenseRow.findElement(By.cssSelector("input[type='text']"))
      demoClick(expNameInput)
      typeSlowly(expNameInput, name)
      val expAmountInput = expenseRow.findElement(By.cssSelector("input[type='number']"))
      demoClick(expAmountInput)
      typeSlowly(expAmountInput, amount)
      pause(shortPause)
      demoClickButton(expenseRow, "Add")
      pause(shortPause)
    }

    pause()

    showPhase("📊 Adding Estimated Expenses")
    injectDemoStyles()

    // Add estimated expenses
    val estimatedCard = findCard("Estimated Expenses")

    val estimated = List(
      ("Groceries", "1200"),
      ("Fuel", "400"),
      ("Dining Out", "300")
    )

    estimated.foreach { case (name, amount) =>
      demoClickButton(findCard("Estimated Expenses"), "+ Add")
      pause(shortPause)

      val estRow = findCard("Estimated Expenses").findElement(By.cssSelector("tr.table-primary"))
      val estNameInput = estRow.findElement(By.cssSelector("input[type='text']"))
      demoClick(estNameInput)
      typeSlowly(estNameInput, name)
      val estAmountInput = estRow.findElement(By.cssSelector("input[type='number']"))
      demoClick(estAmountInput)
      typeSlowly(estAmountInput, amount)
      pause(shortPause)
      demoClickButton(estRow, "Add")
      pause(shortPause)
    }

    pause()

    // ========================================
    // PART 5: Track Savings
    // ========================================

    showPhase("🐷 Tracking Savings")
    injectDemoStyles()

    // Expand Emergency Fund and add transaction
    val emergencyRow = findCard("Planned Savings").findElement(
      By.xpath(".//tr[.//td[contains(text(),'Emergency Fund')]]")
    )
    demoClick(emergencyRow)
    pause()

    demoClickButton(findCard("Planned Savings"), "+ Add")
    pause(shortPause)

    val txnRow = findCard("Planned Savings").findElement(By.cssSelector("tr.table-info"))
    val txnNoteInput = txnRow.findElement(By.cssSelector("input[type='text']"))
    demoClick(txnNoteInput)
    typeSlowly(txnNoteInput, "Monthly deposit")
    val txnAmount = txnRow.findElement(By.cssSelector("input[type='number']"))
    txnAmount.clear()
    demoClick(txnAmount)
    typeSlowly(txnAmount, "500")
    pause(shortPause)
    demoClickButton(txnRow, "Add")
    pause()

    // Collapse Emergency Fund
    findCard("Planned Savings")
      .findElement(By.xpath(".//tr[.//td[contains(text(),'Emergency Fund')]]"))
      .click()
    pause(shortPause)

    // Expand Vacation Fund
    val vacationSavingsRow = findCard("Planned Savings")
      .findElement(By.xpath(".//tr[.//td[contains(text(),'Vacation Fund')]]"))
    demoClick(vacationSavingsRow)
    pause()

    demoClickButton(findCard("Planned Savings"), "+ Add")
    pause(shortPause)

    val vacTxnRow = findCard("Planned Savings").findElement(By.cssSelector("tr.table-info"))
    val vacTxnNoteInput = vacTxnRow.findElement(By.cssSelector("input[type='text']"))
    demoClick(vacTxnNoteInput)
    typeSlowly(vacTxnNoteInput, "Vacation savings")
    val vacTxnAmount = vacTxnRow.findElement(By.cssSelector("input[type='number']"))
    vacTxnAmount.clear()
    demoClick(vacTxnAmount)
    typeSlowly(vacTxnAmount, "200")
    pause(shortPause)
    demoClickButton(vacTxnRow, "Add")
    pause()

    // Collapse
    findCard("Planned Savings")
      .findElement(By.xpath(".//tr[.//td[contains(text(),'Vacation Fund')]]"))
      .click()
    pause()

    // ========================================
    // PART 6: Pay Bills
    // ========================================

    showPhase("✅ Paying Bills")
    injectDemoStyles()

    // Receive income first
    val incomePayRow = findCard("Planned Items").findElement(
      By.xpath(".//tr[.//td[contains(text(),'Monthly Salary')]]")
    )
    demoClickButton(incomePayRow, "Receive")
    pause(shortPause)
    demoClickButton(findCard("Planned Items").findElement(By.cssSelector("tr.table-info")), "Save")
    pause()

    // Pay Rent
    val rentRow = findCard("Planned Items").findElement(By.xpath(".//tr[.//td[contains(text(),'Rent')]]"))
    demoClickButton(rentRow, "Pay")
    pause(shortPause)
    demoClickButton(findCard("Planned Items").findElement(By.cssSelector("tr.table-info")), "Save")
    pause()

    // Pay Utilities with different amount
    val utilitiesRow = findCard("Planned Items").findElement(
      By.xpath(".//tr[.//td[contains(text(),'Utilities')]]")
    )
    demoClickButton(utilitiesRow, "Pay")
    pause(shortPause)

    val utilPayRow = findCard("Planned Items").findElement(By.cssSelector("tr.table-info"))
    val utilInput = utilPayRow.findElement(By.cssSelector("input[type='number']"))
    utilInput.clear()
    demoClick(utilInput)
    typeSlowly(utilInput, "320.50")
    pause(shortPause)
    demoClickButton(utilPayRow, "Save")
    pause()

    // ========================================
    // PART 7: Update Balances & View Dashboard
    // ========================================

    showPhase("📈 Viewing Dashboard")

    driver.get(baseUrl)
    waitForPage("Dashboard")
    injectDemoStyles()
    pause()

    val accountsCard = findCard("Accounts")
    demoClickButton(accountsCard, "Edit Balances")
    pause()

    val balanceInputs = accountsCard.findElements(By.cssSelector("input[type='number']")).asScala.toList

    // Set Main Account balance
    balanceInputs.headOption.foreach { input =>
      input.clear()
      demoClick(input)
      typeSlowly(input, "12500")
    }
    pause(shortPause)

    // Set Euro account balance
    if (balanceInputs.size > 1) {
      balanceInputs(1).clear()
      demoClick(balanceInputs(1))
      typeSlowly(balanceInputs(1), "850")
    }
    pause(shortPause)

    demoClickButton(accountsCard, "Save All")
    pause(longPause)

    // Copy summary
    val cdpDriver = driver.asInstanceOf[org.openqa.selenium.chromium.HasCdp]
    cdpDriver.executeCdpCommand(
      "Browser.grantPermissions",
      java.util.Map.of(
        "permissions",
        java.util.List.of("clipboardReadWrite", "clipboardSanitizedWrite"),
        "origin",
        baseUrl
      )
    )

    val copyBtn = driver.findElement(By.xpath("//button[contains(text(),'Copy Summary')]"))
    demoClick(copyBtn)

    // Wait for button text to change to "Copied!"
    waitFor.until { _ =>
      driver.findElement(By.xpath("//button[contains(text(),'Copied')]")).isDisplayed
    }
    pause(longPause)

    // ========================================
    // PART 8: View Periods
    // ========================================

    showPhase("🏁 Demo Complete!")

    driver.get(s"$baseUrl/periods")
    waitForPage("Periods")
    injectDemoStyles()
    pause(longPause)

    // Give time to stop recording if using remote browser
    if (isRemoteBrowser) {
      println("[Demo] Demo complete - stop your recording now! (3 seconds...)")
      Thread.sleep(3000)
    }

    println("\n" + "=" * 50)
    println("Demo scenario completed successfully!")
    println("=" * 50 + "\n")
  }
}
