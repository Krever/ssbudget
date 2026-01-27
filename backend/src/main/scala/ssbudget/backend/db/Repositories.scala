package ssbudget.backend.db

import doobie.Transactor
import cats.effect.IO
import ssbudget.backend.db.repository.*

final case class Repositories(
    accounts: AccountRepository,
    expenseDefinitions: ExpenseDefinitionRepository,
    periods: PeriodRepository,
    expenseRecords: ExpenseRecordRepository,
    balanceSnapshots: BalanceSnapshotRepository,
    exchangeRates: ExchangeRateRepository,
    savingsAccounts: SavingsAccountRepository,
    savingsTransactions: SavingsTransactionRepository,
)

object Repositories {
  def fromTransactor(xa: Transactor[IO]): Repositories = {
    Repositories(
      accounts = new AccountRepositoryImpl(xa),
      expenseDefinitions = new ExpenseDefinitionRepositoryImpl(xa),
      periods = new PeriodRepositoryImpl(xa),
      expenseRecords = new ExpenseRecordRepositoryImpl(xa),
      balanceSnapshots = new BalanceSnapshotRepositoryImpl(xa),
      exchangeRates = new ExchangeRateRepositoryImpl(xa),
      savingsAccounts = new SavingsAccountRepositoryImpl(xa),
      savingsTransactions = new SavingsTransactionRepositoryImpl(xa),
    )
  }
}
