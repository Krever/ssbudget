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
    savingsTransactions: SavingsTransactionRepository,
    authConfig: AuthConfigRepository,
    sessions: SessionRepository,
    passkeyCredentials: PasskeyCredentialRepository,
    currencySettings: CurrencySettingsRepository,
    oneTimeExpenses: OneTimeExpenseRepository,
    bankConnections: BankConnectionRepository,
    cardGroups: CardGroupRepository,
    bankTransactions: BankTransactionRepository,
    categories: CategoryRepository,
    classificationRules: ClassificationRuleRepository,
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
      savingsTransactions = new SavingsTransactionRepositoryImpl(xa),
      authConfig = new AuthConfigRepositoryImpl(xa),
      sessions = new SessionRepositoryImpl(xa),
      passkeyCredentials = new PasskeyCredentialRepositoryImpl(xa),
      currencySettings = new CurrencySettingsRepositoryImpl(xa),
      oneTimeExpenses = new OneTimeExpenseRepositoryImpl(xa),
      bankConnections = new BankConnectionRepositoryImpl(xa),
      cardGroups = new CardGroupRepositoryImpl(xa),
      bankTransactions = new BankTransactionRepositoryImpl(xa),
      categories = new CategoryRepositoryImpl(xa),
      classificationRules = new ClassificationRuleRepositoryImpl(xa),
    )
  }
}
