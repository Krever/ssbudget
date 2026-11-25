package ssbudget.frontend.services

import org.scalajs.dom
import sttp.client3.*
import sttp.model.{Method, Uri}
import sttp.tapir.DecodeResult
import sttp.tapir.client.sttp.SttpClientInterpreter
import ssbudget.shared.api.*
import ssbudget.shared.model.*

import java.time.LocalDate
import scala.concurrent.{ExecutionContext, Future}

class ApiClient(implicit ec: ExecutionContext) {

  // Enable credentials to include cookies in requests
  private val backend = FetchBackend()

  private val baseUri = uri"${dom.window.location.origin}"

  private val interpreter = SttpClientInterpreter()

  object auth {
    def status(): Future[AuthStatus] = {
      val request = interpreter.toRequest(AuthEndpoints.client.status, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    def setup(password: String): Future[Unit] = {
      val request = interpreter.toRequest(AuthEndpoints.client.setup, Some(baseUri))
      send(request(SetupRequest(password))).map(handleResponse)
    }

    def login(password: String): Future[Unit] = {
      val request = interpreter.toRequest(AuthEndpoints.client.login, Some(baseUri))
      send(request(LoginRequest(password))).map(handleResponse)
    }

    def logout(): Future[Unit] = {
      val request = interpreter.toRequest(AuthEndpoints.client.logout, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    def verifyPasskeyStart(): Future[PasskeyAuthenticationOptions] = {
      val request = interpreter.toRequest(AuthEndpoints.client.verifyPasskeyStart, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    /** Proves the identity of the owner again in this session, for the actions that need more than the session. */
    def confirmIdentity(proof: IdentityProof): Future[Unit] = {
      val request = interpreter.toRequest(AuthEndpoints.client.confirmIdentity, Some(baseUri))
      send(request(proof)).map(handleResponse)
    }

    def changePassword(req: ChangePasswordRequest): Future[Unit] = {
      val request = interpreter.toRequest(AuthEndpoints.client.changePassword, Some(baseUri))
      send(request(req)).map(handleResponse)
    }

    def listPasskeys(): Future[List[PasskeyInfo]] = {
      val request = interpreter.toRequest(AuthEndpoints.client.listPasskeys, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    def deletePasskey(credentialId: String): Future[Unit] = {
      val request = interpreter.toRequest(AuthEndpoints.client.deletePasskey, Some(baseUri))
      send(request(credentialId)).map(handleResponse)
    }

    def listMcpTokens(): Future[List[McpTokenInfo]] = {
      val request = interpreter.toRequest(AuthEndpoints.client.listMcpTokens, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    def createMcpToken(req: CreateMcpTokenRequest): Future[CreatedMcpToken] = {
      val request = interpreter.toRequest(AuthEndpoints.client.createMcpToken, Some(baseUri))
      send(request(req)).map(handleResponse)
    }

    def revokeMcpToken(id: String): Future[Unit] = {
      val request = interpreter.toRequest(AuthEndpoints.client.revokeMcpToken, Some(baseUri))
      send(request(id)).map(handleResponse)
    }

    def registerPasskeyStart(displayName: Option[String]): Future[PasskeyRegistrationOptions] = {
      val request = interpreter.toRequest(AuthEndpoints.client.registerPasskeyStart, Some(baseUri))
      send(request(PasskeyRegisterStartRequest(displayName))).map(handleResponse)
    }

    def registerPasskeyFinish(response: PasskeyRegistrationResponse): Future[Unit] = {
      val request = interpreter.toRequest(AuthEndpoints.client.registerPasskeyFinish, Some(baseUri))
      send(request(response)).map(handleResponse)
    }

    def loginPasskeyStart(): Future[PasskeyAuthenticationOptions] = {
      val request = interpreter.toRequest(AuthEndpoints.client.loginPasskeyStart, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    def loginPasskeyFinish(response: PasskeyAuthenticationResponse): Future[Unit] = {
      val request = interpreter.toRequest(AuthEndpoints.client.loginPasskeyFinish, Some(baseUri))
      send(request(response)).map(handleResponse)
    }
  }

  object accounts {
    def list(): Future[List[Account]] = {
      val request = interpreter.toRequest(Endpoints.client.accounts.list, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    def create(dto: CreateAccount): Future[Account] = {
      val request = interpreter.toRequest(Endpoints.client.accounts.create, Some(baseUri))
      send(request(dto)).map(handleResponse)
    }

    def update(id: AccountId, dto: UpdateAccount): Future[Account] = {
      val request = interpreter.toRequest(Endpoints.client.accounts.update, Some(baseUri))
      send(request((id, dto))).map(handleResponse)
    }

    def updateBalance(id: AccountId, dto: UpdateAccountBalance): Future[Account] = {
      val request = interpreter.toRequest(Endpoints.client.accounts.updateBalance, Some(baseUri))
      send(request((id, dto))).map(handleResponse)
    }

    def delete(id: AccountId): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.client.accounts.delete, Some(baseUri))
      send(request(id)).map(handleResponse)
    }
  }

  object savings {
    def periodBaselines(): Future[List[AccountPeriodBaseline]] = {
      val request = interpreter.toRequest(Endpoints.client.savings.periodBaselines, Some(baseUri))
      send(request(())).map(handleResponse)
    }
  }

  object periods {
    def list(): Future[List[Period]] = {
      val request = interpreter.toRequest(Endpoints.client.periods.list, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    def startNew(asOf: Option[LocalDate]): Future[Period] = {
      val request = interpreter.toRequest(Endpoints.client.periods.startNew, Some(baseUri))
      send(request(asOf)).map(handleResponse)
    }

    def setExpectedEnd(id: PeriodId, expectedEnd: LocalDate): Future[Period] = {
      val request = interpreter.toRequest(Endpoints.client.periods.setExpectedEnd, Some(baseUri))
      send(request((id, SetPeriodExpectedEndRequest(expectedEnd)))).map(handleResponse)
    }

    def setPlannedSaving(id: PeriodId, plannedSavingCents: Long): Future[Period] = {
      val request = interpreter.toRequest(Endpoints.client.periods.setPlannedSaving, Some(baseUri))
      send(request((id, SetPeriodPlannedSavingRequest(plannedSavingCents)))).map(handleResponse)
    }

    def summaries(limit: Option[Int] = None): Future[List[PeriodSummary]] = {
      val request = interpreter.toRequest(Endpoints.client.periods.summaries, Some(baseUri))
      send(request(limit)).map(handleResponse)
    }

    def freeMoneyHistory(): Future[FreeMoneyHistory] = {
      val request = interpreter.toRequest(Endpoints.client.periods.freeMoneyHistory, Some(baseUri))
      send(request(())).map(handleResponse)
    }
  }

  object banking {
    def listAspsps(country: Option[String]): Future[List[Aspsp]] = {
      val request = interpreter.toRequest(Endpoints.client.banking.listAspsps, Some(baseUri))
      send(request(country)).map(handleResponse)
    }

    def connect(req: ConnectBankRequest): Future[ConnectBankResponse] = {
      val request = interpreter.toRequest(Endpoints.client.banking.connect, Some(baseUri))
      send(request(req)).map(handleResponse)
    }

    def callback(code: String, state: String): Future[BankConnectionView] = {
      val request = interpreter.toRequest(Endpoints.client.banking.callback, Some(baseUri))
      send(request(BankCallbackRequest(code, state))).map(handleResponse)
    }

    def connections(): Future[List[BankConnectionView]] = {
      val request = interpreter.toRequest(Endpoints.client.banking.connections, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    def disconnect(id: BankConnectionId): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.client.banking.disconnect, Some(baseUri))
      send(request(id)).map(handleResponse)
    }

    def linkAccount(linkId: BankAccountLinkId, req: LinkAccountRequest): Future[List[BankConnectionView]] = {
      val request = interpreter.toRequest(Endpoints.client.banking.linkAccount, Some(baseUri))
      send(request((linkId, req))).map(handleResponse)
    }

    def sync(id: BankConnectionId): Future[List[BankConnectionView]] = {
      val request = interpreter.toRequest(Endpoints.client.banking.sync, Some(baseUri))
      send(request(id)).map(handleResponse)
    }

    def syncAll(): Future[ImportJob] = {
      val request = interpreter.toRequest(Endpoints.client.banking.syncAll, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    def listCardGroups(): Future[List[CardGroup]] = {
      val request = interpreter.toRequest(Endpoints.client.banking.listCardGroups, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    def createCardGroup(dto: CreateCardGroup): Future[CardGroup] = {
      val request = interpreter.toRequest(Endpoints.client.banking.createCardGroup, Some(baseUri))
      send(request(dto)).map(handleResponse)
    }

    def deleteCardGroup(id: CardGroupId): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.client.banking.deleteCardGroup, Some(baseUri))
      send(request(id)).map(handleResponse)
    }

    def linkCardGroup(id: CardGroupId, req: LinkCardGroupRequest): Future[List[CardGroup]] = {
      val request = interpreter.toRequest(Endpoints.client.banking.linkCardGroup, Some(baseUri))
      send(request((id, req))).map(handleResponse)
    }

    def updateCardGroupLimit(id: CardGroupId, req: UpdateCardGroupLimit): Future[List[CardGroup]] = {
      val request = interpreter.toRequest(Endpoints.client.banking.updateCardGroupLimit, Some(baseUri))
      send(request((id, req))).map(handleResponse)
    }

    def importTransactions(id: BankConnectionId, req: ImportTransactionsRequest): Future[ImportJob] = {
      val request = interpreter.toRequest(Endpoints.client.banking.importTransactions, Some(baseUri))
      send(request((id, req))).map(handleResponse)
    }
  }

  object jobs {
    def list(): Future[List[ImportJob]] = {
      val request = interpreter.toRequest(Endpoints.client.jobs.list, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    def get(id: ImportJobId): Future[ImportJob] = {
      val request = interpreter.toRequest(Endpoints.client.jobs.get, Some(baseUri))
      send(request(id)).map(handleResponse)
    }
  }

  object transactions {
    def query(
        accountUid: Option[String],
        month: Option[String],
        category: Option[String],
        hideInternal: Boolean,
        sort: String,
        asc: Boolean,
        limit: Option[Int],
        q: Option[String],
    ): Future[TransactionListResponse] = {
      val request = interpreter.toRequest(Endpoints.client.transactions.list, Some(baseUri))
      send(request((accountUid, month, category, Some(hideInternal), Some(sort), Some(asc), limit, q))).map(handleResponse)
    }

    def months(): Future[List[String]] = {
      val request = interpreter.toRequest(Endpoints.client.transactions.months, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    def setCategory(id: BankTransactionId, req: SetCategoryRequest): Future[BankTransaction] = {
      val request = interpreter.toRequest(Endpoints.client.transactions.setCategory, Some(baseUri))
      send(request((id, req))).map(handleResponse)
    }

    def setNote(id: BankTransactionId, req: SetNoteRequest): Future[BankTransaction] = {
      val request = interpreter.toRequest(Endpoints.client.transactions.setNote, Some(baseUri))
      send(request((id, req))).map(handleResponse)
    }
  }

  object categories {
    def list(): Future[List[Category]] = {
      val request = interpreter.toRequest(Endpoints.client.categories.list, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    def summaries(): Future[List[CategorySummary]] = {
      val request = interpreter.toRequest(Endpoints.client.categories.summaries, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    def create(dto: CreateCategory): Future[Category] = {
      val request = interpreter.toRequest(Endpoints.client.categories.create, Some(baseUri))
      send(request(dto)).map(handleResponse)
    }

    def update(id: CategoryId, dto: UpdateCategory): Future[Category] = {
      val request = interpreter.toRequest(Endpoints.client.categories.update, Some(baseUri))
      send(request((id, dto))).map(handleResponse)
    }

    def delete(id: CategoryId): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.client.categories.delete, Some(baseUri))
      send(request(id)).map(handleResponse)
    }

    def setOverride(id: CategoryId, dto: SetCategoryOverrideRequest): Future[List[CategorySummary]] = {
      val request = interpreter.toRequest(Endpoints.client.categories.setOverride, Some(baseUri))
      send(request((id, dto))).map(handleResponse)
    }

    def clearOverride(id: CategoryId): Future[List[CategorySummary]] = {
      val request = interpreter.toRequest(Endpoints.client.categories.clearOverride, Some(baseUri))
      send(request(id)).map(handleResponse)
    }
  }

  object rules {
    def list(): Future[List[ClassificationRule]] = {
      val request = interpreter.toRequest(Endpoints.client.rules.list, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    def create(dto: CreateRuleRequest): Future[ClassificationRule] = {
      val request = interpreter.toRequest(Endpoints.client.rules.create, Some(baseUri))
      send(request(dto)).map(handleResponse)
    }

    def update(id: ClassificationRuleId, dto: UpdateRuleRequest): Future[ClassificationRule] = {
      val request = interpreter.toRequest(Endpoints.client.rules.update, Some(baseUri))
      send(request((id, dto))).map(handleResponse)
    }

    def delete(id: ClassificationRuleId): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.client.rules.delete, Some(baseUri))
      send(request(id)).map(handleResponse)
    }

    def reorder(orderedIds: List[ClassificationRuleId]): Future[List[ClassificationRule]] = {
      val request = interpreter.toRequest(Endpoints.client.rules.reorder, Some(baseUri))
      send(request(ReorderRulesRequest(orderedIds))).map(handleResponse)
    }

    def apply(): Future[ApplyRulesResult] = {
      val request = interpreter.toRequest(Endpoints.client.rules.apply, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    def preview(criteria: List[RuleCriterion]): Future[RulePreviewResponse] = {
      val request = interpreter.toRequest(Endpoints.client.rules.preview, Some(baseUri))
      send(request(RulePreviewRequest(criteria))).map(handleResponse)
    }

    def exportRules(): Future[RulesExport] = {
      val request = interpreter.toRequest(Endpoints.client.rules.exportRules, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    def importRules(req: ImportRulesRequest): Future[ImportRulesResult] = {
      val request = interpreter.toRequest(Endpoints.client.rules.importRules, Some(baseUri))
      send(request(req)).map(handleResponse)
    }
  }

  object analytics {
    def config(): Future[AnalyticsConfig] = {
      val request = interpreter.toRequest(Endpoints.client.analytics.config, Some(baseUri))
      send(request(())).map(handleResponse)
    }
  }

  object warnings {
    def list(): Future[List[DataWarning]] = {
      val request = interpreter.toRequest(Endpoints.client.warnings.list, Some(baseUri))
      send(request(())).map(handleResponse)
    }
  }

  object exchangeRates {
    def getAll(): Future[List[ExchangeRate]] = {
      val request = interpreter.toRequest(Endpoints.client.exchangeRates.getAll, Some(baseUri))
      send(request(())).map(handleResponse)
    }
  }

  object currencies {
    def getSettings(): Future[CurrencySettingsResponse] = {
      val request = interpreter.toRequest(Endpoints.client.currencies.getSettings, Some(baseUri))
      send(request(())).map(handleResponse)
    }

    def enable(code: String): Future[CurrencySetting] = {
      val request = interpreter.toRequest(Endpoints.client.currencies.enable, Some(baseUri))
      send(request(EnableCurrencyRequest(code))).map(handleResponse)
    }

    def disable(code: String): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.client.currencies.disable, Some(baseUri))
      send(request(code)).map(handleResponse)
    }

    def setPrimary(code: String): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.client.currencies.setPrimary, Some(baseUri))
      send(request(SetPrimaryCurrencyRequest(code))).map(handleResponse)
    }

    def refreshRates(): Future[ExchangeRatesResponse] = {
      val request = interpreter.toRequest(Endpoints.client.currencies.refreshRates, Some(baseUri))
      send(request(())).map(handleResponse)
    }
  }

  // The auth endpoints answer to a form (a login, a password change) that shows its own error.
  private def reported(method: Method, uri: Uri): Boolean = method != Method.GET && !uri.path.startsWith(List("api", "auth"))

  /** Sends a request. A write that gets no answer (no network, the server is down) is told to the user: without this the page showed the typed value
    * as if it was stored.
    */
  private def send[T](request: Request[T, Any]): Future[Response[T]] =
    backend.send(request).recoverWith { case ex =>
      // The failure carries the same text that is shown, so a caller that reports it too does not make a second line.
      val message = s"No answer from the server (${ex.getMessage})."
      if reported(request.method, request.uri) then Notices.failed(message)
      Future.failed(ApiException(message))
    }

  private def handleResponse[T](response: Response[DecodeResult[Either[ApiError, T]]]): T = {
    val (method, uri)                                      = (response.request.method, response.request.uri)
    def refused(message: String, kind: ErrorKind): Nothing = {
      // Every failed write is shown (see [[Notices]]). A failed read is left to the page, which shows it in place.
      if reported(method, uri) then Notices.failed(message)
      throw ApiException(message, kind)
    }
    response.body match {
      case DecodeResult.Value(Right(value))                                                                                     =>
        if reported(method, uri) then ApiClient.dataChanged()
        value
      // The kind of the error says what to do, not its words. A dead session brings the login back; on the auth endpoints "unauthorized" is
      // the answer to a form (a wrong password), which shows it itself.
      case DecodeResult.Value(Left(error)) if error.kind == ErrorKind.Unauthorized && !uri.path.startsWith(List("api", "auth")) =>
        ApiClient.onSessionEnded()
        throw ApiException("The session ended. Log in again.", ErrorKind.Unauthorized)
      case DecodeResult.Value(Left(error))                                                                                      => refused(error.message, error.kind)
      case failure: DecodeResult.Failure                                                                                        => refused(s"The answer of the server could not be read: $failure", ErrorKind.Failed)
    }
  }
}

object ApiClient {

  /** Called when the server says that the session is not valid any more. The app sets it to go back to the login. It is one hook for every client
    * object: the app has more than one (`Main` and `DataService` each make their own).
    */
  var onSessionEnded: () => Unit = () => ()

  /** How many times the data of the server changed by an action of this page, as far as the page knows. `DataService` reads it to know when what it
    * holds is old: it loads again only then, and not on each visit of a page. Every write that the server accepted counts by itself; a change that
    * comes another way (a background job that ended) is counted by its observer with [[dataChanged]].
    */
  def changes: Int        = changeCount
  def dataChanged(): Unit = changeCount += 1
  private var changeCount = 0
}

/** A request that did not succeed. `kind` says why, as the server named it (see [[ErrorKind]]); a request that got no answer at all is "failed". */
case class ApiException(message: String, kind: ErrorKind = ErrorKind.Failed) extends Exception(message)
