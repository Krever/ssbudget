package ssbudget.e2e

import io.github.bonigarcia.wdm.WebDriverManager
import org.openqa.selenium.{By, JavascriptExecutor, WebDriver, WebElement}
import org.openqa.selenium.chrome.{ChromeDriver, ChromeOptions}
import org.openqa.selenium.support.ui.{ExpectedConditions, WebDriverWait}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Duration
import scala.jdk.CollectionConverters.*

/** Comprehensive demo scenario for recording.
  *
  * This test walks through all major features in a logical order, simulating a realistic user workflow. Designed for screen recording with:
  *   - Visual click indicators (ripple effect)
  *   - Phase banners showing current section
  *   - Authentication flow
  *   - Deliberate pacing between actions
  *
  * As a test it runs with the other specs, with no pauses.
  *
  * For a recording, with the pauses: E2E_DEMO_PACE=1 sbt "e2e/testOnly ssbudget.e2e.DemoScenarioSpec"
  *
  * For headed mode (visible browser, also paced): E2E_HEADED=true sbt "e2e/testOnly ssbudget.e2e.DemoScenarioSpec"
  */
class DemoScenarioSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  import scala.compiletime.uninitialized
  protected var driver: WebDriver    = uninitialized
  private var js: JavascriptExecutor = uninitialized

  protected def baseUrl: String = AuthTestServers.frontendUrl

  /** A recording needs time for the eye; a test run needs none. The pauses are real only for a recording: a visible browser (`E2E_HEADED`), a browser
    * that a person records (`E2E_REMOTE_BROWSER`), or `E2E_DEMO_PACE` set. In a plain test run each pause is zero, so the scenario is a fast test of
    * the whole workflow. A step must thus never depend on a pause: where the page needs time, the step waits for a condition.
    */
  private val recording: Boolean = List("E2E_HEADED", "E2E_REMOTE_BROWSER", "E2E_DEMO_PACE").exists(sys.env.contains)

  private def rest(ms: Int): Unit = if recording then Thread.sleep(ms.toLong)

  // Timing constants for demo (adjust for speed)
  private val shortPause  = 500
  private val mediumPause = 900
  private val longPause   = 1500
  private val phasePause  = 2000

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

    if sys.env.get("E2E_REMOTE_BROWSER").isDefined then {
      // Connect to existing browser with remote debugging
      // Start Chrome with: chrome --remote-debugging-port=9222
      val debugPort = sys.env.getOrElse("E2E_DEBUG_PORT", "9222")
      options.setExperimentalOption("debuggerAddress", s"localhost:$debugPort")
      isRemoteBrowser = true
      println(s"[Demo] Connecting to existing browser on port $debugPort...")
    } else {
      // Launch new browser
      if sys.env.get("E2E_HEADED").isEmpty then {
        options.addArguments("--headless")
      }
      options.addArguments("--no-sandbox", "--disable-dev-shm-usage")
      options.addArguments("--window-size=1400,900")
      isRemoteBrowser = false
    }

    driver = new ChromeDriver(options)
    driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10))
    js = driver.asInstanceOf[JavascriptExecutor]

    if isRemoteBrowser then {
      // Set window size for remote browser
      driver.manage().window().setSize(new org.openqa.selenium.Dimension(1400, 900))
    }
  }

  override def afterEach(): Unit = {
    if driver != null && !isRemoteBrowser then {
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
    """,
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
    """,
    )
    rest(phasePause)
    js.executeScript("""
      const banner = document.querySelector('.demo-phase-banner');
      if (banner) {
        banner.classList.add('hiding');
        setTimeout(() => banner.remove(), 300);
      }
    """)
    rest(350)
  }

  /** Click with visual indicator */
  private def demoClick(element: WebElement): Unit = {
    val rect = element.getRect
    val x    = rect.getX + rect.getWidth / 2
    val y    = rect.getY + rect.getHeight / 2
    showClickAt(x.toInt, y.toInt)
    rest(150)
    // A page that still loads can move the element under the pointer. Then the click goes through the page itself.
    try element.click()
    catch {
      case _: org.openqa.selenium.ElementClickInterceptedException => js.executeScript("arguments[0].click()", element)
    }
    rest(shortPause)
  }

  /** Wait until the page shows what the step did. An element that the page drew again in the meantime counts as "not yet". */
  private def await(condition: => Boolean): Unit = {
    waitFor.until(_ => scala.util.Try(condition).getOrElse(false))
    ()
  }

  /** How many elements in `parent` match the selector, counted by the page. `findElements` is not used for this: with the implicit wait of this
    * driver it waits the full timeout before it answers "none".
    */
  private def countIn(parent: WebElement, css: String): Long =
    js.executeScript("return arguments[0].querySelectorAll(arguments[1]).length", parent, css).asInstanceOf[Long]

  /** Click button by text with visual indicator. The card is found again on each attempt: a page that still loads draws its cards again, and a button
    * that was found a moment before is then gone.
    */
  private def demoClickButton(parent: => WebElement, buttonText: String): Unit = {
    await {
      demoClick(parent.findElement(By.xpath(s".//button[contains(.,'$buttonText')]")))
      true
    }
  }

  /** Type text slowly for visibility */
  private def typeSlowly(element: WebElement, text: String): Unit = {
    text.foreach { char =>
      element.sendKeys(char.toString)
      rest(25)
    }
  }

  private def pause(ms: Int = mediumPause): Unit = rest(ms)

  // ============ Page Helpers ============

  private def waitForPage(title: String): Unit = {
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h4"), title))
    rest(300)
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
    if isRemoteBrowser then {
      println("[Demo] Page loaded - start your recording now! (3 seconds...)")
      rest(3000)
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

    // The card says one of two things after it has loaded.
    def periodCard = findCardByDiv("Current Period")
    await(periodCard.getText.contains("No active period") || periodCard.getText.contains("Started"))
    if periodCard.getText.contains("No active period") then {
      demoClickButton(periodCard, "Start New Period")
      await(periodCard.getText.contains("Started"))
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

    def currenciesCard = findCardByH5("Currencies")

    // Add USD currency
    val currencyInput = currenciesCard.findElement(By.cssSelector("input[type='text']"))
    demoClick(currencyInput)
    typeSlowly(currencyInput, "USD")
    pause(shortPause)
    demoClickButton(currenciesCard, "Add Currency")
    await(findCardByH5("Currencies").getText.contains("USD"))
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
    def bankCard = findCard("Bank Accounts")
    demoClickButton(bankCard, "+ Add")
    pause(shortPause)

    val bankAddRow    = bankCard.findElement(By.cssSelector("tbody tr.table-primary"))
    val bankNameInput = bankAddRow.findElement(By.cssSelector("input[type='text']"))
    demoClick(bankNameInput)
    typeSlowly(bankNameInput, "Main Account")
    pause(shortPause)
    demoClickButton(bankAddRow, "Add")
    // The new account is in the table, and the row for adding is closed, before the next account is added.
    await(bankCard.getText.contains("Main Account") && countIn(bankCard, "tbody tr.table-primary") == 0)
    pause()

    // Add EUR account
    demoClickButton(bankCard, "+ Add")
    pause(shortPause)

    val eurAddRow    = bankCard.findElement(By.cssSelector("tbody tr.table-primary"))
    val eurNameInput = eurAddRow.findElement(By.cssSelector("input[type='text']"))
    demoClick(eurNameInput)
    typeSlowly(eurNameInput, "Euro Savings")
    val eurSelect    = eurAddRow.findElement(By.cssSelector("select"))
    demoClick(eurSelect)
    eurSelect.findElement(By.xpath(".//option[text()='EUR']")).click()
    pause(shortPause)
    demoClickButton(eurAddRow, "Add")
    await(bankCard.getText.contains("Euro Savings") && countIn(bankCard, "tbody tr.table-primary") == 0)
    pause()

    showPhase("💰 Adding Savings Accounts")
    injectDemoStyles()

    List("Emergency Fund", "Vacation Fund").foreach { name =>
      demoClickButton(findCard("Savings & Investments"), "+ Add")
      pause(shortPause)

      val addRow    = findCard("Savings & Investments").findElement(By.cssSelector("tbody tr.table-success"))
      val nameInput = addRow.findElement(By.cssSelector("input[type='text']"))
      demoClick(nameInput)
      typeSlowly(nameInput, name)
      pause(shortPause)
      demoClickButton(addRow, "Add")
      await {
        val card = findCard("Savings & Investments")
        card.getText.contains(name) && countIn(card, "tbody tr.table-success") == 0
      }
      pause()
    }

    // The plan is made of category budgets, and a category budget reads bank transactions. This scenario has no bank connection, so it shows no
    // plan entries.

    // ========================================
    // PART 4: Update Balances & View Dashboard
    // ========================================

    showPhase("📈 Viewing Dashboard")

    driver.get(baseUrl)
    waitForPage("Dashboard")
    injectDemoStyles()
    pause()

    def accountsCard = findCard("Accounts")
    // The accounts are in the card before the edit starts: the edit takes the balances that are loaded at that moment. The two new accounts
    // have no money yet, so the ledger folds them into one line; the count on the group row says that they are loaded.
    await(accountsCard.getText.contains("2 accounts"))
    demoClickButton(accountsCard, "Edit Balances")
    await(countIn(accountsCard, "input[type='number']") >= 2)
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
    if balanceInputs.size > 1 then {
      balanceInputs(1).clear()
      demoClick(balanceInputs(1))
      typeSlowly(balanceInputs(1), "850")
    }
    pause(shortPause)

    demoClickButton(accountsCard, "Save All")
    await(countIn(accountsCard, "input[type='number']") == 0 && accountsCard.getText.contains("12,500.00"))
    pause(longPause)

    // Copy summary
    val cdpDriver = driver.asInstanceOf[org.openqa.selenium.chromium.HasCdp]
    cdpDriver.executeCdpCommand(
      "Browser.grantPermissions",
      java.util.Map.of(
        "permissions",
        java.util.List.of("clipboardReadWrite", "clipboardSanitizedWrite"),
        "origin",
        baseUrl,
      ),
    )

    val copyBtn = driver.findElement(By.xpath("//button[contains(text(),'Copy Summary')]"))
    demoClick(copyBtn)

    // Wait for button text to change to "Copied!"
    waitFor.until { _ =>
      driver.findElement(By.xpath("//button[contains(text(),'Copied')]")).isDisplayed
    }
    pause(longPause)

    // ========================================
    // PART 5: View Periods
    // ========================================

    showPhase("🏁 Demo Complete!")

    driver.get(s"$baseUrl/periods")
    waitForPage("Periods")
    injectDemoStyles()
    pause(longPause)

    // Give time to stop recording if using remote browser
    if isRemoteBrowser then {
      println("[Demo] Demo complete - stop your recording now! (3 seconds...)")
      rest(3000)
    }

    println("\n" + "=" * 50)
    println("Demo scenario completed successfully!")
    println("=" * 50 + "\n")
  }
}
