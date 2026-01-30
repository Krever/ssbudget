package ssbudget.e2e

import io.github.bonigarcia.wdm.WebDriverManager
import org.openqa.selenium.{By, WebDriver}
import org.openqa.selenium.chrome.{ChromeDriver, ChromeOptions}
import org.openqa.selenium.support.ui.{ExpectedConditions, WebDriverWait}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Duration

/** Auth e2e tests - run separately from main suite since they need auth enabled. Run with: sbt "e2e/testOnly ssbudget.e2e.AuthSpec"
  */
class AuthSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  import scala.compiletime.uninitialized
  protected var driver: WebDriver = uninitialized

  protected def baseUrl: String = AuthTestServers.frontendUrl

  override def beforeAll(): Unit = {
    AuthTestServers.startAll()
    WebDriverManager.chromedriver().setup()
  }

  override def afterAll(): Unit = {
    AuthTestServers.stopAll()
  }

  override def beforeEach(): Unit = {
    val options = new ChromeOptions()
    options.addArguments("--headless", "--no-sandbox", "--disable-dev-shm-usage", "--window-size=1920,1080")
    driver = new ChromeDriver(options)
    driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10))
  }

  override def afterEach(): Unit = {
    if driver != null then driver.quit()
    // Reset database between tests
    AuthTestServers.resetDatabase()
  }

  protected def waitFor: WebDriverWait = new WebDriverWait(driver, Duration.ofSeconds(10))

  behavior of "Authentication"

  it should "show setup page on first visit" in {
    driver.get(baseUrl)

    // Wait for setup page to appear
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h3"), "SSBudget Setup"))

    // Should see password setup form
    driver.findElement(By.id("password")).isDisplayed shouldBe true
    driver.findElement(By.id("confirm")).isDisplayed shouldBe true
    driver.findElement(By.xpath("//button[text()='Create Password']")).isDisplayed shouldBe true
  }

  it should "setup password and auto-login" in {
    driver.get(baseUrl)

    // Wait for setup page
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h3"), "SSBudget Setup"))

    // Enter password
    driver.findElement(By.id("password")).sendKeys("testpassword123")
    driver.findElement(By.id("confirm")).sendKeys("testpassword123")

    // Click create
    driver.findElement(By.xpath("//button[text()='Create Password']")).click()

    // Should redirect to dashboard after auto-login
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h4"), "Dashboard"))
    Thread.sleep(500)

    // Should see navbar with logout button
    waitFor.until(ExpectedConditions.visibilityOfElementLocated(By.xpath("//button[text()='Logout']")))
  }

  it should "logout and show login page" in {
    // First setup and login
    driver.get(baseUrl)
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h3"), "SSBudget Setup"))
    driver.findElement(By.id("password")).sendKeys("testpassword123")
    driver.findElement(By.id("confirm")).sendKeys("testpassword123")
    driver.findElement(By.xpath("//button[text()='Create Password']")).click()
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h4"), "Dashboard"))
    Thread.sleep(500)

    // Click logout (wait for it to be visible first)
    waitFor.until(ExpectedConditions.elementToBeClickable(By.xpath("//button[text()='Logout']"))).click()

    // Should show login page
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h3"), "SSBudget"))
    waitFor.until(ExpectedConditions.visibilityOfElementLocated(By.id("password")))
    waitFor.until(ExpectedConditions.visibilityOfElementLocated(By.xpath("//button[text()='Sign In']")))
  }

  it should "login with correct password" in {
    // First setup
    driver.get(baseUrl)
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h3"), "SSBudget Setup"))
    driver.findElement(By.id("password")).sendKeys("testpassword123")
    driver.findElement(By.id("confirm")).sendKeys("testpassword123")
    driver.findElement(By.xpath("//button[text()='Create Password']")).click()
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h4"), "Dashboard"))
    Thread.sleep(500)

    // Logout
    waitFor.until(ExpectedConditions.elementToBeClickable(By.xpath("//button[text()='Logout']"))).click()
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h3"), "SSBudget"))

    // Login
    waitFor.until(ExpectedConditions.visibilityOfElementLocated(By.id("password"))).sendKeys("testpassword123")
    driver.findElement(By.xpath("//button[text()='Sign In']")).click()

    // Should show dashboard
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h4"), "Dashboard"))
  }

  it should "show error for wrong password" in {
    // First setup
    driver.get(baseUrl)
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h3"), "SSBudget Setup"))
    driver.findElement(By.id("password")).sendKeys("testpassword123")
    driver.findElement(By.id("confirm")).sendKeys("testpassword123")
    driver.findElement(By.xpath("//button[text()='Create Password']")).click()
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h4"), "Dashboard"))
    Thread.sleep(500)

    // Logout
    driver.findElement(By.xpath("//button[text()='Logout']")).click()
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h3"), "SSBudget"))

    // Try wrong password
    driver.findElement(By.id("password")).sendKeys("wrongpassword")
    driver.findElement(By.xpath("//button[text()='Sign In']")).click()

    // Should show error
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.className("alert-danger"), "Invalid password"))

    // Should still be on login page
    driver.findElement(By.id("password")).isDisplayed shouldBe true
  }

  it should "show password mismatch error on setup" in {
    driver.get(baseUrl)
    waitFor.until(ExpectedConditions.textToBePresentInElementLocated(By.tagName("h3"), "SSBudget Setup"))

    // Enter mismatched passwords
    driver.findElement(By.id("password")).sendKeys("password1")
    driver.findElement(By.id("confirm")).sendKeys("password2")

    // Button should be disabled when passwords don't match
    val button = driver.findElement(By.xpath("//button[text()='Create Password']"))
    button.isEnabled shouldBe false
  }
}
