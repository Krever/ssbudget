package ssbudget.e2e

import io.github.bonigarcia.wdm.WebDriverManager
import org.openqa.selenium.By
import org.openqa.selenium.chrome.{ChromeDriver, ChromeOptions}
import org.openqa.selenium.logging.{LogType, LoggingPreferences}
import java.util.logging.Level
import org.openqa.selenium.support.ui.{ExpectedConditions, WebDriverWait}
import org.openqa.selenium.virtualauthenticator.{HasVirtualAuthenticator, VirtualAuthenticatorOptions}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Duration

/** Passkey e2e tests - tests WebAuthn/passkey registration and authentication. Run with: sbt "e2e/testOnly ssbudget.e2e.PasskeySpec"
  */
class PasskeySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  import scala.compiletime.uninitialized
  protected var driver: ChromeDriver = uninitialized

  protected def baseUrl: String = AuthTestServers.frontendUrl

  override def beforeAll(): Unit = {
    AuthTestServers.startAll()
    WebDriverManager.chromedriver().setup()
  }

  override def afterAll(): Unit = {
    AuthTestServers.stopAll()
  }

  override def beforeEach(): Unit = {
    val options  = new ChromeOptions()
    options.addArguments("--headless", "--no-sandbox", "--disable-dev-shm-usage", "--window-size=1920,1080")
    // Enable virtual authenticator support
    options.addArguments("--enable-features=WebAuthenticationRemoteDesktopSupport")
    // Enable console logging
    val logPrefs = new LoggingPreferences()
    logPrefs.enable(LogType.BROWSER, Level.ALL)
    options.setCapability("goog:loggingPrefs", logPrefs)
    driver = new ChromeDriver(options)
    driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10))
  }

  override def afterEach(): Unit = {
    if driver != null then driver.quit()
    AuthTestServers.resetDatabase()
  }

  protected def waitFor: WebDriverWait = new WebDriverWait(driver, Duration.ofSeconds(10))

  /** Setup password and login (prerequisite for passkey tests) */
  private def setupAndLogin(): Unit = {
    driver.get(baseUrl)
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h3"), "SSBudget Setup"))
    driver.findElement(By.id("password")).sendKeys("testpassword123")
    driver.findElement(By.id("confirm")).sendKeys("testpassword123")
    driver.findElement(By.xpath("//button[text()='Create Password']")).click()
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h4"), "Dashboard"))
    Thread.sleep(500)
  }

  /** Add a virtual authenticator to the browser session */
  private def addVirtualAuthenticator(): Unit = {
    val authenticatorOptions = new VirtualAuthenticatorOptions()
      .setTransport(VirtualAuthenticatorOptions.Transport.INTERNAL)
      .setProtocol(VirtualAuthenticatorOptions.Protocol.CTAP2)
      .setHasResidentKey(true)
      .setHasUserVerification(true)
      .setIsUserVerified(true)

    driver.asInstanceOf[HasVirtualAuthenticator].addVirtualAuthenticator(authenticatorOptions)
  }

  /** Navigate to settings page */
  private def goToSettings(): Unit = {
    // Wait for navbar to be visible first
    waitFor.until(ExpectedConditions.visibilityOfElementLocated(By.className("navbar")))
    // Use link text or contains href since absoluteUrlForPage returns full URL
    driver.findElement(By.linkText("Settings")).click()
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h2"), "Settings"))
    Thread.sleep(300)
  }

  /** Logout the current session */
  private def logout(): Unit = {
    // Logout button is in the navbar on any page
    waitFor.until(ExpectedConditions.visibilityOfElementLocated(By.className("navbar")))
    driver.findElement(By.xpath("//nav//button[text()='Logout']")).click()
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h3"), "SSBudget"))
    Thread.sleep(300)
  }

  behavior of "Passkey Authentication"

  it should "show Add Passkey button on settings page" in {
    addVirtualAuthenticator()
    setupAndLogin()
    goToSettings()

    // Should see passkey section with Add Passkey button
    waitFor.until(ExpectedConditions.visibilityOfElementLocated(By.xpath("//button[text()='Add Passkey']")))

    // Should show message about no passkeys
    driver.findElement(By.xpath("//*[contains(text(),'No passkeys registered')]")).isDisplayed shouldBe true
  }

  it should "register a passkey" in {
    addVirtualAuthenticator()
    setupAndLogin()
    goToSettings()

    // Enter passkey name and click Add Passkey
    val passkeyNameInput = driver.findElement(By.xpath("//input[@placeholder='Passkey name (optional)']"))
    passkeyNameInput.sendKeys("Test Passkey")
    driver.findElement(By.xpath("//button[text()='Add Passkey']")).click()

    // Wait for success message
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.className("alert-success"), "Passkey added"))

    // Should see the passkey in the list
    waitFor.until(ExpectedConditions.visibilityOfElementLocated(By.xpath("//*[contains(text(),'Test Passkey')]")))
  }

  it should "register a passkey without name" in {
    addVirtualAuthenticator()
    setupAndLogin()
    goToSettings()

    // Click Add Passkey without entering a name
    driver.findElement(By.xpath("//button[text()='Add Passkey']")).click()

    // Wait for success message
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.className("alert-success"), "Passkey added"))

    // Should see the passkey in the list (unnamed)
    waitFor.until(ExpectedConditions.visibilityOfElementLocated(By.xpath("//*[contains(text(),'Unnamed passkey')]")))
  }

  it should "login with passkey" in {
    addVirtualAuthenticator()
    setupAndLogin()
    goToSettings()

    // Register a passkey first
    driver.findElement(By.xpath("//input[@placeholder='Passkey name (optional)']")).sendKeys("Login Test Passkey")
    driver.findElement(By.xpath("//button[text()='Add Passkey']")).click()
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.className("alert-success"), "Passkey added"))

    // Logout
    logout()

    // Should see login page with passkey option
    waitFor.until(ExpectedConditions.visibilityOfElementLocated(By.xpath("//button[contains(text(),'Sign in with Passkey')]")))

    // Click sign in with passkey
    driver.findElement(By.xpath("//button[contains(text(),'Sign in with Passkey')]")).click()

    // Give time for WebAuthn to complete
    Thread.sleep(3000)

    // Check for any error message
    val errors = driver.findElements(By.className("alert-danger"))
    if errors.size() > 0 then {
      val errorText = errors.get(0).getText
      fail(s"Passkey login failed with error: $errorText")
    }

    // Verify login success by checking that the navbar is visible (only shown when logged in)
    // The app doesn't auto-redirect to / after login, so URL may still be /settings
    waitFor.until(ExpectedConditions.visibilityOfElementLocated(By.className("navbar")))

    // Verify we can navigate to Dashboard (confirming we're actually logged in)
    driver.findElement(By.linkText("Dashboard")).click()
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h4"), "Dashboard"))
  }

  it should "delete a passkey" in {
    addVirtualAuthenticator()
    setupAndLogin()
    goToSettings()

    // Register a passkey first
    driver.findElement(By.xpath("//input[@placeholder='Passkey name (optional)']")).sendKeys("Passkey To Delete")
    driver.findElement(By.xpath("//button[text()='Add Passkey']")).click()
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.className("alert-success"), "Passkey added"))
    Thread.sleep(300)

    // Find and click the Delete button for this passkey
    val passkeyItem = driver.findElement(By.xpath("//*[contains(text(),'Passkey To Delete')]/ancestor::li"))
    passkeyItem.findElement(By.xpath(".//button[text()='Delete']")).click()

    // Wait for success message
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.className("alert-success"), "Passkey deleted"))

    // Should show no passkeys message again
    waitFor.until(ExpectedConditions.visibilityOfElementLocated(By.xpath("//*[contains(text(),'No passkeys registered')]")))
  }

  it should "not show passkey login option when no passkeys registered" in {
    addVirtualAuthenticator()
    setupAndLogin()
    logout()

    // Should NOT see passkey login button (no passkeys registered)
    val passkeyButtons = driver.findElements(By.xpath("//button[contains(text(),'Sign in with Passkey')]"))
    passkeyButtons.size() shouldBe 0
  }

  it should "register multiple passkeys" in {
    addVirtualAuthenticator()
    setupAndLogin()
    goToSettings()

    // Register first passkey
    driver.findElement(By.xpath("//input[@placeholder='Passkey name (optional)']")).sendKeys("Passkey 1")
    driver.findElement(By.xpath("//button[text()='Add Passkey']")).click()
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.className("alert-success"), "Passkey added"))
    Thread.sleep(500)

    // Dismiss success message
    driver.findElement(By.xpath("//div[contains(@class,'alert-success')]//button[@class='btn-close']")).click()
    Thread.sleep(300)

    // Register second passkey
    val passkeyNameInput = driver.findElement(By.xpath("//input[@placeholder='Passkey name (optional)']"))
    passkeyNameInput.clear()
    passkeyNameInput.sendKeys("Passkey 2")
    driver.findElement(By.xpath("//button[text()='Add Passkey']")).click()
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.className("alert-success"), "Passkey added"))

    // Should see both passkeys in the list
    driver.findElement(By.xpath("//*[contains(text(),'Passkey 1')]")).isDisplayed shouldBe true
    driver.findElement(By.xpath("//*[contains(text(),'Passkey 2')]")).isDisplayed shouldBe true
  }
}
