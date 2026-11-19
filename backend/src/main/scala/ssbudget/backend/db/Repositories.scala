package ssbudget.backend.db

import doobie.Transactor
import cats.effect.IO
import ssbudget.backend.db.repository.*

final case class Repositories(
    accounts: AccountRepository,
    periods: PeriodRepository,
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
    analyticsState: AnalyticsStateRepository,
    dailyBudgetSnapshots: DailyBudgetSnapshotRepository,
    mcpTokens: McpTokenRepository,
)

object Repositories {
  def fromTransactor(xa: Transactor[IO]): Repositories = {
    Repositories(
      accounts = new AccountRepositoryImpl(xa),
      periods = new PeriodRepositoryImpl(xa),
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
      analyticsState = new AnalyticsStateRepositoryImpl(xa),
      dailyBudgetSnapshots = new DailyBudgetSnapshotRepositoryImpl(xa),
      mcpTokens = new McpTokenRepositoryImpl(xa),
    )
  }
}
