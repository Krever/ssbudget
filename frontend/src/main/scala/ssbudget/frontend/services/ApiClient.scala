package ssbudget.frontend.services

import org.scalajs.dom
import sttp.client3.*
import sttp.tapir.DecodeResult
import sttp.tapir.client.sttp.SttpClientInterpreter
import ssbudget.shared.api.*
import ssbudget.shared.model.*

import scala.concurrent.{ExecutionContext, Future}

class ApiClient(implicit ec: ExecutionContext) {

  // Enable credentials to include cookies in requests
  private val backend = FetchBackend()

  private val baseUri = uri"${dom.window.location.origin}"

  private val interpreter = SttpClientInterpreter()

  object auth {
    def status(): Future[AuthStatus] = {
      val request = interpreter.toRequest(AuthEndpoints.client.status, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def setup(password: String): Future[Unit] = {
      val request = interpreter.toRequest(AuthEndpoints.client.setup, Some(baseUri))
      backend.send(request(SetupRequest(password))).map(handleResponse)
    }

    def login(password: String): Future[Unit] = {
      val request = interpreter.toRequest(AuthEndpoints.client.login, Some(baseUri))
      backend.send(request(LoginRequest(password))).map(handleResponse)
    }

    def logout(): Future[Unit] = {
      val request = interpreter.toRequest(AuthEndpoints.client.logout, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def listPasskeys(): Future[List[PasskeyInfo]] = {
      val request = interpreter.toRequest(AuthEndpoints.client.listPasskeys, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def deletePasskey(credentialId: String): Future[Unit] = {
      val request = interpreter.toRequest(AuthEndpoints.client.deletePasskey, Some(baseUri))
      backend.send(request(credentialId)).map(handleResponse)
    }

    def registerPasskeyStart(displayName: Option[String]): Future[PasskeyRegistrationOptions] = {
      val request = interpreter.toRequest(AuthEndpoints.client.registerPasskeyStart, Some(baseUri))
      backend.send(request(PasskeyRegisterStartRequest(displayName))).map(handleResponse)
    }

    def registerPasskeyFinish(response: PasskeyRegistrationResponse): Future[Unit] = {
      val request = interpreter.toRequest(AuthEndpoints.client.registerPasskeyFinish, Some(baseUri))
      backend.send(request(response)).map(handleResponse)
    }

    def loginPasskeyStart(): Future[PasskeyAuthenticationOptions] = {
      val request = interpreter.toRequest(AuthEndpoints.client.loginPasskeyStart, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def loginPasskeyFinish(response: PasskeyAuthenticationResponse): Future[Unit] = {
      val request = interpreter.toRequest(AuthEndpoints.client.loginPasskeyFinish, Some(baseUri))
      backend.send(request(response)).map(handleResponse)
    }
  }

  object accounts {
    def list(): Future[List[Account]] = {
      val request = interpreter.toRequest(Endpoints.client.accounts.list, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def create(dto: CreateAccount): Future[Account] = {
      val request = interpreter.toRequest(Endpoints.client.accounts.create, Some(baseUri))
      backend.send(request(dto)).map(handleResponse)
    }

    def update(id: AccountId, dto: UpdateAccount): Future[Account] = {
      val request = interpreter.toRequest(Endpoints.client.accounts.update, Some(baseUri))
      backend.send(request((id, dto))).map(handleResponse)
    }

    def updateBalance(id: AccountId, dto: UpdateAccountBalance): Future[Account] = {
      val request = interpreter.toRequest(Endpoints.client.accounts.updateBalance, Some(baseUri))
      backend.send(request((id, dto))).map(handleResponse)
    }

    def delete(id: AccountId): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.client.accounts.delete, Some(baseUri))
      backend.send(request(id)).map(handleResponse)
    }
  }

  object budgetItems {
    def list(): Future[List[BudgetItemDefinition]] = {
      val request = interpreter.toRequest(Endpoints.client.budgetItems.list, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def create(dto: CreateBudgetItem): Future[BudgetItemDefinition] = {
      val request = interpreter.toRequest(Endpoints.client.budgetItems.create, Some(baseUri))
      backend.send(request(dto)).map(handleResponse)
    }

    def update(id: ExpenseDefId, dto: UpdateBudgetItem): Future[BudgetItemDefinition] = {
      val request = interpreter.toRequest(Endpoints.client.budgetItems.update, Some(baseUri))
      backend.send(request((id, dto))).map(handleResponse)
    }

    def delete(id: ExpenseDefId): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.client.budgetItems.delete, Some(baseUri))
      backend.send(request(id)).map(handleResponse)
    }
  }

  object expenseRecords {
    def listCurrent(): Future[List[ExpenseRecord]] = {
      val request = interpreter.toRequest(Endpoints.client.expenseRecords.listCurrent, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def pay(expenseDefId: ExpenseDefId, dto: PayBudgetItem): Future[ExpenseRecord] = {
      val request = interpreter.toRequest(Endpoints.client.expenseRecords.pay, Some(baseUri))
      backend.send(request((expenseDefId, dto))).map(handleResponse)
    }

    def unpay(expenseDefId: ExpenseDefId): Future[ExpenseRecord] = {
      val request = interpreter.toRequest(Endpoints.client.expenseRecords.unpay, Some(baseUri))
      backend.send(request(expenseDefId)).map(handleResponse)
    }
  }

  object periods {
    def list(): Future[List[Period]] = {
      val request = interpreter.toRequest(Endpoints.client.periods.list, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def startNew(): Future[Period] = {
      val request = interpreter.toRequest(Endpoints.client.periods.startNew, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }
  }

  object savingsTransactions {
    def listCurrent(): Future[List[SavingsTransaction]] = {
      val request = interpreter.toRequest(Endpoints.client.savingsTransactions.listCurrent, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def create(dto: CreateSavingsTransaction): Future[SavingsTransaction] = {
      val request = interpreter.toRequest(Endpoints.client.savingsTransactions.create, Some(baseUri))
      backend.send(request(dto)).map(handleResponse)
    }

    def delete(id: SavingsTransactionId): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.client.savingsTransactions.delete, Some(baseUri))
      backend.send(request(id)).map(handleResponse)
    }
  }

  object savings {
    def periodChange(): Future[Money] = {
      val request = interpreter.toRequest(Endpoints.client.savings.periodChange, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }
  }

  object banking {
    def listAspsps(country: Option[String]): Future[List[Aspsp]] = {
      val request = interpreter.toRequest(Endpoints.client.banking.listAspsps, Some(baseUri))
      backend.send(request(country)).map(handleResponse)
    }

    def connect(req: ConnectBankRequest): Future[ConnectBankResponse] = {
      val request = interpreter.toRequest(Endpoints.client.banking.connect, Some(baseUri))
      backend.send(request(req)).map(handleResponse)
    }

    def callback(code: String, state: String): Future[BankConnectionView] = {
      val request = interpreter.toRequest(Endpoints.client.banking.callback, Some(baseUri))
      backend.send(request(BankCallbackRequest(code, state))).map(handleResponse)
    }

    def connections(): Future[List[BankConnectionView]] = {
      val request = interpreter.toRequest(Endpoints.client.banking.connections, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def disconnect(id: BankConnectionId): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.client.banking.disconnect, Some(baseUri))
      backend.send(request(id)).map(handleResponse)
    }

    def linkAccount(linkId: BankAccountLinkId, req: LinkAccountRequest): Future[List[BankConnectionView]] = {
      val request = interpreter.toRequest(Endpoints.client.banking.linkAccount, Some(baseUri))
      backend.send(request((linkId, req))).map(handleResponse)
    }

    def sync(id: BankConnectionId): Future[List[BankConnectionView]] = {
      val request = interpreter.toRequest(Endpoints.client.banking.sync, Some(baseUri))
      backend.send(request(id)).map(handleResponse)
    }

    def syncAll(): Future[SyncAllResult] = {
      val request = interpreter.toRequest(Endpoints.client.banking.syncAll, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def listCardGroups(): Future[List[CardGroup]] = {
      val request = interpreter.toRequest(Endpoints.client.banking.listCardGroups, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def createCardGroup(dto: CreateCardGroup): Future[CardGroup] = {
      val request = interpreter.toRequest(Endpoints.client.banking.createCardGroup, Some(baseUri))
      backend.send(request(dto)).map(handleResponse)
    }

    def deleteCardGroup(id: CardGroupId): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.client.banking.deleteCardGroup, Some(baseUri))
      backend.send(request(id)).map(handleResponse)
    }

    def linkCardGroup(id: CardGroupId, req: LinkCardGroupRequest): Future[List[CardGroup]] = {
      val request = interpreter.toRequest(Endpoints.client.banking.linkCardGroup, Some(baseUri))
      backend.send(request((id, req))).map(handleResponse)
    }

    def importTransactions(id: BankConnectionId, req: ImportTransactionsRequest): Future[ImportResult] = {
      val request = interpreter.toRequest(Endpoints.client.banking.importTransactions, Some(baseUri))
      backend.send(request((id, req))).map(handleResponse)
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
    ): Future[TransactionListResponse] = {
      val request = interpreter.toRequest(Endpoints.client.transactions.list, Some(baseUri))
      backend.send(request((accountUid, month, category, Some(hideInternal), Some(sort), Some(asc), limit))).map(handleResponse)
    }

    def months(): Future[List[String]] = {
      val request = interpreter.toRequest(Endpoints.client.transactions.months, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def setCategory(id: BankTransactionId, req: SetCategoryRequest): Future[BankTransaction] = {
      val request = interpreter.toRequest(Endpoints.client.transactions.setCategory, Some(baseUri))
      backend.send(request((id, req))).map(handleResponse)
    }

    def setNote(id: BankTransactionId, req: SetNoteRequest): Future[BankTransaction] = {
      val request = interpreter.toRequest(Endpoints.client.transactions.setNote, Some(baseUri))
      backend.send(request((id, req))).map(handleResponse)
    }
  }

  object categories {
    def list(): Future[List[Category]] = {
      val request = interpreter.toRequest(Endpoints.client.categories.list, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def summaries(): Future[List[CategorySummary]] = {
      val request = interpreter.toRequest(Endpoints.client.categories.summaries, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def create(dto: CreateCategory): Future[Category] = {
      val request = interpreter.toRequest(Endpoints.client.categories.create, Some(baseUri))
      backend.send(request(dto)).map(handleResponse)
    }

    def update(id: CategoryId, dto: UpdateCategory): Future[Category] = {
      val request = interpreter.toRequest(Endpoints.client.categories.update, Some(baseUri))
      backend.send(request((id, dto))).map(handleResponse)
    }

    def delete(id: CategoryId): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.client.categories.delete, Some(baseUri))
      backend.send(request(id)).map(handleResponse)
    }
  }

  object rules {
    def list(): Future[List[ClassificationRule]] = {
      val request = interpreter.toRequest(Endpoints.client.rules.list, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def create(dto: CreateRuleRequest): Future[ClassificationRule] = {
      val request = interpreter.toRequest(Endpoints.client.rules.create, Some(baseUri))
      backend.send(request(dto)).map(handleResponse)
    }

    def update(id: ClassificationRuleId, dto: UpdateRuleRequest): Future[ClassificationRule] = {
      val request = interpreter.toRequest(Endpoints.client.rules.update, Some(baseUri))
      backend.send(request((id, dto))).map(handleResponse)
    }

    def delete(id: ClassificationRuleId): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.client.rules.delete, Some(baseUri))
      backend.send(request(id)).map(handleResponse)
    }

    def reorder(orderedIds: List[ClassificationRuleId]): Future[List[ClassificationRule]] = {
      val request = interpreter.toRequest(Endpoints.client.rules.reorder, Some(baseUri))
      backend.send(request(ReorderRulesRequest(orderedIds))).map(handleResponse)
    }

    def apply(): Future[ApplyRulesResult] = {
      val request = interpreter.toRequest(Endpoints.client.rules.apply, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def preview(criteria: List[RuleCriterion]): Future[RulePreviewResponse] = {
      val request = interpreter.toRequest(Endpoints.client.rules.preview, Some(baseUri))
      backend.send(request(RulePreviewRequest(criteria))).map(handleResponse)
    }

    def exportRules(): Future[RulesExport] = {
      val request = interpreter.toRequest(Endpoints.client.rules.exportRules, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def importRules(req: ImportRulesRequest): Future[ImportRulesResult] = {
      val request = interpreter.toRequest(Endpoints.client.rules.importRules, Some(baseUri))
      backend.send(request(req)).map(handleResponse)
    }
  }

  object analytics {
    def overview(months: Option[Int]): Future[AnalyticsResponse] = {
      val request = interpreter.toRequest(Endpoints.client.analytics.overview, Some(baseUri))
      backend.send(request(months)).map(handleResponse)
    }
  }

  object oneTimeExpenses {
    def list(): Future[List[OneTimeExpense]] = {
      val request = interpreter.toRequest(Endpoints.client.oneTimeExpenses.list, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def create(dto: CreateOneTimeExpense): Future[OneTimeExpense] = {
      val request = interpreter.toRequest(Endpoints.client.oneTimeExpenses.create, Some(baseUri))
      backend.send(request(dto)).map(handleResponse)
    }

    def update(id: OneTimeExpenseId, dto: UpdateOneTimeExpense): Future[OneTimeExpense] = {
      val request = interpreter.toRequest(Endpoints.client.oneTimeExpenses.update, Some(baseUri))
      backend.send(request((id, dto))).map(handleResponse)
    }

    def delete(id: OneTimeExpenseId): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.client.oneTimeExpenses.delete, Some(baseUri))
      backend.send(request(id)).map(handleResponse)
    }
  }

  object exchangeRates {
    def getAll(): Future[List[ExchangeRate]] = {
      val request = interpreter.toRequest(Endpoints.client.exchangeRates.getAll, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }
  }

  object currencies {
    def getSettings(): Future[CurrencySettingsResponse] = {
      val request = interpreter.toRequest(Endpoints.client.currencies.getSettings, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def enable(code: String): Future[CurrencySetting] = {
      val request = interpreter.toRequest(Endpoints.client.currencies.enable, Some(baseUri))
      backend.send(request(EnableCurrencyRequest(code))).map(handleResponse)
    }

    def disable(code: String): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.client.currencies.disable, Some(baseUri))
      backend.send(request(code)).map(handleResponse)
    }

    def setPrimary(code: String): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.client.currencies.setPrimary, Some(baseUri))
      backend.send(request(SetPrimaryCurrencyRequest(code))).map(handleResponse)
    }

    def refreshRates(): Future[ExchangeRatesResponse] = {
      val request = interpreter.toRequest(Endpoints.client.currencies.refreshRates, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }
  }

  private def handleResponse[T](response: Response[DecodeResult[Either[String, T]]]): T = {
    response.body match {
      case DecodeResult.Value(Right(value)) => value
      case DecodeResult.Value(Left(error))  => throw ApiException(error)
      case failure: DecodeResult.Failure    => throw ApiException(s"Decode failure: $failure")
    }
  }
}

case class ApiException(message: String) extends Exception(message)
