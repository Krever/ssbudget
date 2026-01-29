package ssbudget.e2e

import org.openqa.selenium.By
import org.openqa.selenium.chrome.ChromeOptions

import java.io.File
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class DatabaseSpec extends E2ESpec {

  private var downloadDir: Path = _

  override def beforeEach(): Unit = {
    // Create temp download directory
    downloadDir = Files.createTempDirectory("ssbudget-e2e-download-")

    // Configure Chrome to download to our temp directory
    val options = new ChromeOptions()
    options.addArguments("--headless", "--no-sandbox", "--disable-dev-shm-usage")
    options.setExperimentalOption(
      "prefs",
      java.util.Map.of(
        "download.default_directory",
        downloadDir.toAbsolutePath.toString,
        "download.prompt_for_download",
        false,
        "download.directory_upgrade",
        true,
      ),
    )

    import io.github.bonigarcia.wdm.WebDriverManager
    import org.openqa.selenium.chrome.ChromeDriver
    WebDriverManager.chromedriver().setup()
    driver = new ChromeDriver(options)
    driver.manage().timeouts().implicitlyWait(java.time.Duration.ofSeconds(10))
  }

  override def afterEach(): Unit = {
    super.afterEach()
    // Cleanup download directory
    if downloadDir != null then {
      Files.walk(downloadDir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
    }
  }

  "Database export" should "download a valid SQLite file" in {
    // Setup: create an account so we have data
    addBankAccount("Export Test Account")

    // Go to settings and click export
    driver.get(s"$baseUrl/settings")
    waitFor.until(_ => driver.findElement(By.tagName("h2")).getText.contains("Settings"))

    val dataCard   = findCardByH5("Data")
    val exportLink = dataCard.findElement(By.xpath(".//a[contains(.,'Export Database')]"))
    exportLink.click()

    // Wait for download to complete (check for .db file)
    var downloadedFile: Option[File] = None
    var attempts                     = 0
    while downloadedFile.isEmpty && attempts < 30 do {
      Thread.sleep(500)
      val files = downloadDir.toFile.listFiles()
      if files != null then {
        downloadedFile = files.find(f => f.getName.endsWith(".db") && !f.getName.endsWith(".crdownload"))
      }
      attempts += 1
    }

    downloadedFile shouldBe defined
    val file = downloadedFile.get

    // Verify it's a valid SQLite file (check header)
    val bytes  = Files.readAllBytes(file.toPath)
    bytes.length should be > 100
    val header = new String(bytes.take(16), "UTF-8")
    header should startWith("SQLite format 3")
  }

  "Database import" should "restore data from a backup" in {
    // Step 1: Create initial data
    ensurePeriodExists()
    addBankAccount("Original Account")
    addPlannedExpense("Original Expense", 100)

    // Step 2: Export the database
    driver.get(s"$baseUrl/settings")
    waitFor.until(_ => driver.findElement(By.tagName("h2")).getText.contains("Settings"))

    val dataCard   = findCardByH5("Data")
    val exportLink = dataCard.findElement(By.xpath(".//a[contains(.,'Export Database')]"))
    exportLink.click()

    // Wait for download
    var backupFile: Option[File] = None
    var attempts                 = 0
    while backupFile.isEmpty && attempts < 30 do {
      Thread.sleep(500)
      val files = downloadDir.toFile.listFiles()
      if files != null then {
        backupFile = files.find(f => f.getName.endsWith(".db") && !f.getName.endsWith(".crdownload"))
      }
      attempts += 1
    }
    backupFile shouldBe defined

    // Step 3: Add more data (this will be lost after restore)
    addBankAccount("New Account After Backup")
    addPlannedExpense("New Expense After Backup", 200)

    // Verify the new data exists
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")
    val bankCardBefore = findCard("Bank Accounts")
    bankCardBefore.getText should include("New Account After Backup")

    // Step 4: Import the backup
    driver.get(s"$baseUrl/settings")
    waitFor.until(_ => driver.findElement(By.tagName("h2")).getText.contains("Settings"))

    val dataCardAgain = findCardByH5("Data")
    val fileInput     = dataCardAgain.findElement(By.cssSelector("input[type='file']"))

    // Send the backup file path to the hidden input
    fileInput.sendKeys(backupFile.get.getAbsolutePath)

    // Wait for import to complete (success message should appear)
    Thread.sleep(2000)
    val alerts = driver.findElements(By.cssSelector(".alert-success")).asScala
    alerts.exists(_.getText.contains("imported successfully")) shouldBe true

    // Step 5: Verify the data was restored (new data should be gone, original should exist)
    driver.get(s"$baseUrl/accounts")
    waitForPage("Accounts")
    Thread.sleep(500)

    val bankCardAfter = findCard("Bank Accounts")
    bankCardAfter.getText should include("Original Account")
    bankCardAfter.getText should not include "New Account After Backup"

    driver.get(s"$baseUrl/budget")
    waitForPage("Budget")
    Thread.sleep(500)

    val plannedCard = findCard("Planned Items")
    plannedCard.getText should include("Original Expense")
    plannedCard.getText should not include "New Expense After Backup"
  }

  it should "show error for invalid file" in {
    driver.get(s"$baseUrl/settings")
    waitFor.until(_ => driver.findElement(By.tagName("h2")).getText.contains("Settings"))

    // Create an invalid file (not SQLite)
    val invalidFile = Files.createTempFile(downloadDir, "invalid", ".db")
    Files.write(invalidFile, "this is not a sqlite file".getBytes)

    val dataCard  = findCardByH5("Data")
    val fileInput = dataCard.findElement(By.cssSelector("input[type='file']"))
    fileInput.sendKeys(invalidFile.toAbsolutePath.toString)

    // Wait for error message
    Thread.sleep(2000)
    val alerts = driver.findElements(By.cssSelector(".alert-danger")).asScala
    alerts.exists(_.getText.toLowerCase.contains("invalid")) shouldBe true
  }
}
