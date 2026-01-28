package ssbudget.frontend.services

import org.scalajs.dom
import sttp.client3.*
import sttp.tapir.DecodeResult
import sttp.tapir.client.sttp.SttpClientInterpreter
import ssbudget.shared.api.*
import ssbudget.shared.model.*

import scala.concurrent.{ExecutionContext, Future}

class ApiClient(implicit ec: ExecutionContext) {

  private val backend = FetchBackend()

  private val baseUri = uri"${dom.window.location.origin}"

  private val interpreter = SttpClientInterpreter()

  object accounts {
    def list(): Future[List[Account]] = {
      val request = interpreter.toRequest(Endpoints.accounts.list, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def create(dto: CreateAccount): Future[AccountResponse] = {
      val request = interpreter.toRequest(Endpoints.accounts.create, Some(baseUri))
      backend.send(request(dto)).map(handleResponse)
    }
  }

  object balances {
    def listLatest(): Future[List[BalanceSnapshot]] = {
      val request = interpreter.toRequest(Endpoints.balances.listLatest, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def create(dto: CreateBalanceSnapshot): Future[BalanceSnapshot] = {
      val request = interpreter.toRequest(Endpoints.balances.create, Some(baseUri))
      backend.send(request(dto)).map(handleResponse)
    }
  }

  object budgetItems {
    def list(): Future[List[BudgetItemDefinition]] = {
      val request = interpreter.toRequest(Endpoints.budgetItems.list, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def create(dto: CreateBudgetItem): Future[BudgetItemDefinition] = {
      val request = interpreter.toRequest(Endpoints.budgetItems.create, Some(baseUri))
      backend.send(request(dto)).map(handleResponse)
    }

    def update(id: ExpenseDefId, dto: UpdateBudgetItem): Future[BudgetItemDefinition] = {
      val request = interpreter.toRequest(Endpoints.budgetItems.update, Some(baseUri))
      backend.send(request((id, dto))).map(handleResponse)
    }

    def delete(id: ExpenseDefId): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.budgetItems.delete, Some(baseUri))
      backend.send(request(id)).map(handleResponse)
    }
  }

  object expenseRecords {
    def listCurrent(): Future[List[ExpenseRecord]] = {
      val request = interpreter.toRequest(Endpoints.expenseRecords.listCurrent, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def pay(expenseDefId: ExpenseDefId, dto: PayBudgetItem): Future[ExpenseRecord] = {
      val request = interpreter.toRequest(Endpoints.expenseRecords.pay, Some(baseUri))
      backend.send(request((expenseDefId, dto))).map(handleResponse)
    }

    def unpay(expenseDefId: ExpenseDefId): Future[ExpenseRecord] = {
      val request = interpreter.toRequest(Endpoints.expenseRecords.unpay, Some(baseUri))
      backend.send(request(expenseDefId)).map(handleResponse)
    }
  }

  object periods {
    def list(): Future[List[Period]] = {
      val request = interpreter.toRequest(Endpoints.periods.list, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def startNew(): Future[Period] = {
      val request = interpreter.toRequest(Endpoints.periods.startNew, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }
  }

  object savingsAccounts {
    def list(): Future[List[SavingsAccount]] = {
      val request = interpreter.toRequest(Endpoints.savingsAccounts.list, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def create(dto: CreateSavingsAccount): Future[SavingsAccount] = {
      val request = interpreter.toRequest(Endpoints.savingsAccounts.create, Some(baseUri))
      backend.send(request(dto)).map(handleResponse)
    }

    def update(id: SavingsAccountId, dto: UpdateSavingsAccount): Future[SavingsAccount] = {
      val request = interpreter.toRequest(Endpoints.savingsAccounts.update, Some(baseUri))
      backend.send(request((id, dto))).map(handleResponse)
    }

    def updateBalance(id: SavingsAccountId, dto: UpdateSavingsAccountBalance): Future[SavingsAccount] = {
      val request = interpreter.toRequest(Endpoints.savingsAccounts.updateBalance, Some(baseUri))
      backend.send(request((id, dto))).map(handleResponse)
    }

    def delete(id: SavingsAccountId): Future[Unit] = {
      val request = interpreter.toRequest(Endpoints.savingsAccounts.delete, Some(baseUri))
      backend.send(request(id)).map(handleResponse)
    }
  }

  object savingsTransactions {
    def listCurrent(): Future[List[SavingsTransaction]] = {
      val request = interpreter.toRequest(Endpoints.savingsTransactions.listCurrent, Some(baseUri))
      backend.send(request(())).map(handleResponse)
    }

    def create(dto: CreateSavingsTransaction): Future[SavingsTransactionResponse] = {
      val request = interpreter.toRequest(Endpoints.savingsTransactions.create, Some(baseUri))
      backend.send(request(dto)).map(handleResponse)
    }

    def delete(id: SavingsTransactionId): Future[SavingsAccount] = {
      val request = interpreter.toRequest(Endpoints.savingsTransactions.delete, Some(baseUri))
      backend.send(request(id)).map(handleResponse)
    }
  }

  object exchangeRate {
    def get(): Future[Option[ExchangeRate]] = {
      val request = interpreter.toRequest(Endpoints.exchangeRate.get, Some(baseUri))
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
