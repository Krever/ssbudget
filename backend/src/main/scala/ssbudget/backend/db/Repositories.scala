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
    authConfig: AuthConfigRepository,
    sessions: SessionRepository,
    passkeyCredentials: PasskeyCredentialRepository,
    currencySettings: CurrencySettingsRepository,
    bankConnections: BankConnectionRepository,
    cardGroups: CardGroupRepository,
    bankTransactions: BankTransactionRepository,
    categories: CategoryRepository,
    categoryBudgetOverrides: CategoryBudgetOverrideRepository,
    classificationRules: ClassificationRuleRepository,
    importJobs: ImportJobRepository,
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
      authConfig = new AuthConfigRepositoryImpl(xa),
      sessions = new SessionRepositoryImpl(xa),
      passkeyCredentials = new PasskeyCredentialRepositoryImpl(xa),
      currencySettings = new CurrencySettingsRepositoryImpl(xa),
      bankConnections = new BankConnectionRepositoryImpl(xa),
      cardGroups = new CardGroupRepositoryImpl(xa),
      bankTransactions = new BankTransactionRepositoryImpl(xa),
      categories = new CategoryRepositoryImpl(xa),
      categoryBudgetOverrides = new CategoryBudgetOverrideRepositoryImpl(xa),
      classificationRules = new ClassificationRuleRepositoryImpl(xa),
      importJobs = new ImportJobRepositoryImpl(xa),
    )
  }
}
