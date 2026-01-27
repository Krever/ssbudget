package ssbudget.e2e

import io.github.bonigarcia.wdm.WebDriverManager
import org.openqa.selenium.{By, WebDriver}
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
  protected val baseUrl           = sys.env.getOrElse("E2E_BASE_URL", "http://localhost:3002")

  override def beforeAll(): Unit = WebDriverManager.chromedriver().setup()

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

  protected def findCard(headerText: String) =
    driver.findElement(By.xpath(s"//span[text()='$headerText']/ancestor::div[contains(@class,'card')]"))

  protected def findCardByDiv(headerText: String) =
    driver.findElement(By.xpath(s"//div[text()='$headerText']/ancestor::div[contains(@class,'card')]"))

  protected def rows(parent: org.openqa.selenium.WebElement) =
    parent.findElements(By.cssSelector("tbody tr")).asScala.toList

  protected def click(parent: org.openqa.selenium.WebElement, buttonText: String): Unit = {
    parent.findElement(By.xpath(s".//button[contains(text(),'$buttonText')]")).click()
    Thread.sleep(300)
  }
}
