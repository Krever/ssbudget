package ssbudget.backend.banking

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.http4s.*
import org.http4s.implicits.*
import org.http4s.headers.`Content-Type`
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.backend.Routes
import ssbudget.backend.auth.SessionService
import ssbudget.backend.db.{Database, Repositories}
import ssbudget.backend.service.CurrencyService
import ssbudget.shared.api.{BankCallbackRequest, ConnectBankRequest, ImportTransactionsRequest, LinkAccountRequest}
import ssbudget.shared.model.*
import sttp.client3.httpclient.cats.HttpClientCatsBackend

import java.nio.file.{Files, Path}
import java.security.KeyPairGenerator
import java.util.Base64

/** Compose test: the real [[EnableBankingClient]] (JWT signing + sttp) drives a WireMock-stubbed Enable Banking API through connect → callback →
  * sync, then the real HTTP [[Routes]] verify a bank-linked account's balance can't be edited manually (and can again after unlinking).
  */
class BankingIntegrationSpec extends AnyFreeSpec with Matchers with BeforeAndAfterAll {

  private var wm: WireMockServer                      = scala.compiletime.uninitialized
  private var dbFile: Path                            = scala.compiletime.uninitialized
  private var releaseDb: IO[Unit]                     = IO.unit
  private var releaseBackend: IO[Unit]                = IO.unit
  private var repos: Repositories                     = scala.compiletime.uninitialized
  private var routes: org.http4s.HttpApp[IO]          = scala.compiletime.uninitialized
  private var banking: BankingService                 = scala.compiletime.uninitialized
  private var importService: TransactionImportService = scala.compiletime.uninitialized

  private def testPrivateKeyPem(): String = {
    val kp  = KeyPairGenerator.getInstance("RSA")
    kp.initialize(2048)
    val der = kp.generateKeyPair().getPrivate.getEncoded // PKCS#8 for RSA
    val b64 = Base64.getMimeEncoder(64, "\n".getBytes).encodeToString(der)
    s"-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"
  }

  override def beforeAll(): Unit = {
    wm = new WireMockServer(options().dynamicPort())
    wm.start()

    // Stub the Enable Banking endpoints the flow touches.
    wm.stubFor(post(urlPathEqualTo("/auth")).willReturn(okJson("""{"url":"https://bank.example/authorize?x=1"}""")))
    wm.stubFor(
      post(urlPathEqualTo("/sessions")).willReturn(
        okJson(
          """{"session_id":"sess-1","access":{"valid_until":"2026-12-31"},
            |"accounts":[{"uid":"acc-uid-1","name":"Main","currency":"PLN","account_id":{"iban":"PL1"}}]}""".stripMargin,
        ),
      ),
    )
    wm.stubFor(
      get(urlPathEqualTo("/accounts/acc-uid-1/details"))
        .willReturn(okJson("""{"name":"Main","product":"Current","account_id":{"iban":"PL1"},"currency":"PLN"}""")),
    )
    wm.stubFor(
      get(urlPathEqualTo("/accounts/acc-uid-1/balances"))
        .willReturn(okJson("""{"balances":[{"balance_type":"CLAV","balance_amount":{"amount":"5000.00","currency":"PLN"}}]}""")),
    )
    wm.stubFor(
      get(urlPathEqualTo("/accounts/acc-uid-1/transactions")).willReturn(
        okJson(
          // tx-1 outgoing to a shop (no creditor IBAN) → external. tx-2 incoming salary → external.
          // tx-3 incoming FROM our own account PL1 → internal. tx-4 is a BLIK purchase (outgoing) that only carries OUR OWN
          //   account as debtor_account — the regression guard: it must NOT be flagged internal.
          """{"transactions":[
            |  {"entry_reference":"tx-1","transaction_amount":{"amount":"12.34","currency":"PLN"},"credit_debit_indicator":"DBIT",
            |   "status":"BOOK","booking_date":"2026-01-10","creditor":{"name":"Shop"},"remittance_information":["Groceries"]},
            |  {"entry_reference":"tx-2","transaction_amount":{"amount":"100.00","currency":"PLN"},"credit_debit_indicator":"CRDT",
            |   "status":"BOOK","booking_date":"2026-01-11","debtor":{"name":"Employer"}},
            |  {"entry_reference":"tx-3","transaction_amount":{"amount":"250.00","currency":"PLN"},"credit_debit_indicator":"CRDT",
            |   "status":"BOOK","booking_date":"2026-01-12","debtor_account":{"iban":"PL1"}},
            |  {"entry_reference":"tx-4","transaction_amount":{"amount":"30.00","currency":"PLN"},"credit_debit_indicator":"DBIT",
            |   "status":"BOOK","booking_date":"2026-01-13","creditor":{"name":"BLIK Shop"},"debtor_account":{"iban":"PL1"}}
            |]}""".stripMargin,
        ),
      ),
    )

    val cfg = EnableBankingConfig("test-app", testPrivateKeyPem(), s"http://localhost:${wm.port()}", "https://localhost/cb")

    dbFile = Files.createTempFile("ssbudget-it-", ".db")
    val (xa, relDb)            = Database.migrateAndTransactor(s"jdbc:sqlite:${dbFile.toAbsolutePath}").allocated.unsafeRunSync()
    val (sttpBackend, relSttp) = HttpClientCatsBackend.resource[IO]().allocated.unsafeRunSync()
    releaseDb = relDb
    releaseBackend = relSttp

    repos = Repositories.fromTransactor(xa)
    val jwt             = EnableBankingJwt.create(cfg).unsafeRunSync()
    val ebClient        = new EnableBankingClient(cfg, jwt, sttpBackend)
    banking = new BankingService(repos, Some(ebClient))
    val ruleEngine      = new RuleEngineService(repos)
    importService = new TransactionImportService(repos, Some(ebClient), ruleEngine)
    val currencyService = new CurrencyService(repos, sttpBackend)
    routes = Routes
      .make(
        repos,
        xa,
        dbFile.toAbsolutePath.toString,
        SessionService(repos.sessions),
        currencyService,
        banking,
        importService,
        ruleEngine,
        testMode = true,
      )
      .orNotFound
  }

  override def afterAll(): Unit = {
    if wm != null then wm.stop()
    releaseBackend.attempt.unsafeRunSync()
    releaseDb.attempt.unsafeRunSync()
    if dbFile != null then Files.deleteIfExists(dbFile)
  }

  private def putBalance(accountId: AccountId, cents: Long): Response[IO] = {
    val req = Request[IO](Method.PUT, Uri.unsafeFromString(s"/api/accounts/${accountId.value}/balance"))
      .withEntity(s"""{"newBalanceCents":$cents}""")
      .withContentType(`Content-Type`(MediaType.application.json))
    routes.run(req).unsafeRunSync()
  }

  "a bank-linked account syncs its balance and rejects manual balance edits until unlinked" in {
    val accountId = AccountId("app-acc-1")

    // 1. Connect (real EnableBankingClient → WireMock /auth), read back the pending connection's CSRF state.
    banking.connect(ConnectBankRequest("Test Bank", "PL")).unsafeRunSync() shouldBe a[Right[?, ?]]
    val conn  = repos.bankConnections.findAll.unsafeRunSync().head
    val state = conn.authState.getOrElse(fail("pending connection should carry an auth state"))

    // 2. Callback exchanges the code for a session (WireMock /sessions) and stores the authorized account link.
    banking.callback(BankCallbackRequest("code-xyz", state)).unsafeRunSync() shouldBe a[Right[?, ?]]
    val linkId = repos.bankConnections.findLinksByConnection(conn.id).unsafeRunSync().head.id

    // 3. Create an app account and link the bank account to it.
    repos.accounts.create(Account(accountId, "Main PLN", Currency.PLN, AccountRole.Spending, 0L, None, BalanceSource.Manual, None)).unsafeRunSync()
    banking.linkAccount(linkId, LinkAccountRequest(BankLinkTarget.Account(accountId))).unsafeRunSync() shouldBe a[Right[?, ?]]

    // 4. Sync pulls details + balances (WireMock) and mirrors the balance onto the account.
    banking.sync(conn.id).unsafeRunSync() shouldBe a[Right[?, ?]]
    val synced = repos.accounts.findById(accountId).unsafeRunSync().getOrElse(fail("account missing"))
    synced.balanceCents shouldBe 500000 // 5000.00 PLN
    synced.balanceSource shouldBe BalanceSource.Bank

    // 5. The HTTP guard: editing a bank-driven balance is rejected.
    val rejected = putBalance(accountId, 999)
    rejected.status.code shouldBe 400
    rejected.bodyText.compile.string.unsafeRunSync() should include("bank")
    repos.accounts.findById(accountId).unsafeRunSync().map(_.balanceCents) shouldBe Some(500000) // unchanged

    // 5b. Import transactions through the real client (WireMock): parsing, dedup, and the internal-transfer rule end-to-end.
    val imported = importService.importTransactions(conn.id, ImportTransactionsRequest(Some(1))).unsafeRunSync()
    imported.map(_.totalImported) shouldBe Right(4)
    val txs      = repos.bankTransactions.list(Some("acc-uid-1"), None, None).unsafeRunSync()
    txs.size shouldBe 4
    txs.find(_.entryReference.contains("tx-3")).map(_.internal) shouldBe Some(true)  // incoming from own account
    txs.find(_.entryReference.contains("tx-4")).map(_.internal) shouldBe Some(false) // BLIK purchase — not a transfer
    txs.find(_.entryReference.contains("tx-1")).map(_.internal) shouldBe Some(false) // external shop
    // Re-import refreshes in place, never duplicates.
    importService.importTransactions(conn.id, ImportTransactionsRequest(Some(1))).unsafeRunSync().map(_.totalImported) shouldBe Right(0)
    repos.bankTransactions.list(Some("acc-uid-1"), None, None).unsafeRunSync().size shouldBe 4

    // 6. After unlinking, the account is Manual again and editable.
    banking.linkAccount(linkId, LinkAccountRequest(BankLinkTarget.Unlinked)).unsafeRunSync() shouldBe a[Right[?, ?]]
    val allowed = putBalance(accountId, 12345)
    allowed.status.code shouldBe 200
    repos.accounts.findById(accountId).unsafeRunSync().map(_.balanceCents) shouldBe Some(12345)
  }
}
