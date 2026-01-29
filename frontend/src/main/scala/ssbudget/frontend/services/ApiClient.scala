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

    def create(dto: CreateAccount): Future[AccountResponse] = {
      val request = interpreter.toRequest(Endpoints.client.accounts.create, Some(baseUri))
      backend.send(request(dto)).map(handleResponse)
    }
  }

  object balances {
    def listLatest(): Future[List[BalanceSnapshot]] = {
      val request = interpreter.toRequest(Endpoints.client.balances.listLatest, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def create(dto: CreateBalanceSnapshot): Future[BalanceSnapshot] = {
      val request = interpreter.toRequest(Endpoints.client.balances.create, Some(baseUri))
      backend.send(request(dto)).map(handleResponse)
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

  object savingsAccounts {
    def list(): Future[List[SavingsAccount]] = {
      val request = interpreter.toRequest(Endpoints.client.savingsAccounts.list, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def create(dto: CreateSavingsAccount): Future[SavingsAccount] = {
      val request = interpreter.toRequest(Endpoints.client.savingsAccounts.create, Some(baseUri))
      backend.send(request(dto)).map(handleResponse)
    }

    def update(id: SavingsAccountId, dto: UpdateSavingsAccount): Future[SavingsAccount] = {
      val request = interpreter.toRequest(Endpoints.client.savingsAccounts.update, Some(baseUri))
      backend.send(request((id, dto))).map(handleResponse)
    }

    def updateBalance(id: SavingsAccountId, dto: UpdateSavingsAccountBalance): Future[SavingsAccount] = {
      val request = interpreter.toRequest(Endpoints.client.savingsAccounts.updateBalance, Some(baseUri))
      backend.send(request((id, dto))).map(handleResponse)
    }

    def delete(id: SavingsAccountId): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.client.savingsAccounts.delete, Some(baseUri))
      backend.send(request(id)).map(handleResponse)
    }
  }

  object savingsTransactions {
    def listCurrent(): Future[List[SavingsTransaction]] = {
      val request = interpreter.toRequest(Endpoints.client.savingsTransactions.listCurrent, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def create(dto: CreateSavingsTransaction): Future[SavingsTransactionResponse] = {
      val request = interpreter.toRequest(Endpoints.client.savingsTransactions.create, Some(baseUri))
      backend.send(request(dto)).map(handleResponse)
    }

    def delete(id: SavingsTransactionId): Future[SavingsAccount] = {
      val request = interpreter.toRequest(Endpoints.client.savingsTransactions.delete, Some(baseUri))
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
