package ssbudget.frontend.services

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.state.Val
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.shared.api.{CategorySummary, TransactionListResponse}
import ssbudget.shared.model.*

import java.time.{Instant, LocalDate}
import scala.concurrent.Future

/** The figures that the page computes from what the server gives: saved, market result, still to pay, the saving reserve, free money and the daily
  * budget. They are the `final` members of [[DataService]], so this spec gives the trait fixed inputs and reads the results. It runs on Node, as
  * JavaScript, which is the form the browser runs.
  */
class DataServiceSpec extends AnyFreeSpec with Matchers {

  private val PLN = Currency.PLN
  private val EUR = Currency.EUR

  private def account(id: String, role: AccountRole, cents: Long, currency: Currency = PLN) =
    Account(AccountId(id), id, currency, role, cents, BalanceSource.Manual, None)

  private def category(id: String, budgetType: Option[CategoryBudgetType], expected: Long, remaining: Long) =
    CategorySummary(
      Category(CategoryId(id), id, None, budgetType = budgetType),
      expectedMonthlyCents = expected,
      currentPeriodSpentCents = 0L,
      lastPeriodSpentCents = 0L,
      currency = PLN,
      overrideRemainingCents = Some(remaining),
    )

  /** The inputs of one scenario. The primary currency is PLN and one EUR is 4 PLN. */
  private class Fixture(plannedSaving: Long) extends DataService {
    // The period ends 10 days from the day the test runs, so "days remaining" is 10 on any day.
    private val period = Period(PeriodId("p1"), Instant.parse("2026-01-01T00:00:00Z"), DataService.today.plusDays(10), None, plannedSaving)

    val savingsAccounts: Signal[List[Account]]    = Val(
      List(account("s-pln", AccountRole.Savings, 300000L), account("s-eur", AccountRole.Savings, 100000L, EUR)),
    )
    val investmentAccounts: Signal[List[Account]] = Val(List(account("inv", AccountRole.Investment, 800000L)))
    val holdingAccounts: Signal[List[Account]]    = Val(Nil)
    val spendingAccounts: Signal[List[Account]]   = Val(List(account("main", AccountRole.Spending, 500000L)))
    val accounts: Signal[List[Account]]           = Val(Nil)

    // The balances at the start of the period: the PLN savings grew by 500.00, the EUR savings by 100.00 EUR, the investment by 1000.00.
    val savingsBaselines: Signal[Map[AccountId, Money]] =
      Val(Map(AccountId("s-pln") -> Money(250000L, PLN), AccountId("s-eur") -> Money(90000L, EUR), AccountId("inv") -> Money(700000L, PLN)))
    // Of the 1000.00 that the investment grew, 600.00 was a deposit.
    val investmentMoved: Signal[Map[AccountId, Long]]   = Val(Map(AccountId("inv") -> 60000L))
    // The EUR was 3.80 when the period started, and is 4 now: the 900.00 EUR of the start gained 180.00. The server gives this figure.
    val currencyEffects: Signal[Map[AccountId, Long]]   = Val(Map(AccountId("s-pln") -> 0L, AccountId("s-eur") -> 18000L, AccountId("inv") -> 0L))

    val exchangeRates: Signal[Map[Currency, Double]] = Val(Map(EUR -> 4.0))
    val primaryCurrency: Signal[Currency]            = Val(PLN)
    val currentPeriod: Signal[Option[Period]]        = Val(Some(period))
    val periods: Signal[List[Period]]                = Val(List(period))
    val bankAccountBalance: Signal[Money]            = Val(Money(500000L, PLN))
    val totalBalance: Signal[Money]                  = Val(Money(0L, PLN))

    // A bill with 1200.00 still to pay, an income budget with 800.00 still to arrive, and a category that is not a budget: its amount counts nowhere.
    val categorySummaries: Signal[List[CategorySummary]]  = Val(
      List(
        category("rent", Some(CategoryBudgetType.Bill), expected = 200000L, remaining = 120000L),
        category("second-pay", Some(CategoryBudgetType.Bill), expected = -300000L, remaining = -80000L),
        category("no-budget", None, expected = 50000L, remaining = 99900L),
      ),
    )
    val budgetedCategories: Signal[List[CategorySummary]] = Val(Nil)

    val freeMoneyHistory: Signal[Map[PeriodId, List[FreeMoneyPoint]]] = Val(Map.empty)
    val currencySettings: Signal[List[CurrencySetting]]               = Val(Nil)
    val availableCurrencies: Signal[List[(String, String)]]           = Val(Nil)
    val enabledCurrencies: Signal[List[Currency]]                     = Val(Nil)

    // The writes and the loads: no figure reads them.
    def initialize(): Future[Unit]                                                                      = ???
    def refreshIfStale(): Future[Unit]                                                                  = ???
    def addAccount(name: String, currency: Currency): Future[Unit]                                      = ???
    def deleteAccount(accountId: AccountId): Future[Unit]                                               = ???
    def updateAccountBalance(accountId: AccountId, amountCents: Long): Future[Unit]                     = ???
    def startNewPeriod(asOf: Option[LocalDate]): Future[Unit]                                           = ???
    def setPeriodExpectedEnd(id: PeriodId, expectedEnd: LocalDate): Future[Unit]                        = ???
    def setPeriodPlannedSaving(id: PeriodId, plannedSavingCents: Long): Future[Unit]                    = ???
    def enableCurrency(code: String): Future[Unit]                                                      = ???
    def disableCurrency(code: String): Future[Unit]                                                     = ???
    def setPrimaryCurrency(code: String): Future[Unit]                                                  = ???
    def refreshExchangeRates(): Future[Unit]                                                            = ???
    def addSavingsAccount(name: String, currency: Currency, role: AccountRole): Future[Unit]            = ???
    def updateAccount(id: AccountId, name: String, currency: Currency, role: AccountRole): Future[Unit] = ???
    def setCategoryBudgetOverride(categoryId: CategoryId, remainingCents: Long): Future[Unit]           = ???
    def clearCategoryBudgetOverride(categoryId: CategoryId): Future[Unit]                               = ???
    def categoryPeriodTransactions(categoryId: CategoryId, limit: Int): Future[TransactionListResponse] = ???
    def uncategorizedTransactions(limit: Int): Future[TransactionListResponse]                          = ???
    def setTransactionCategory(txId: BankTransactionId, categoryId: Option[CategoryId]): Future[Unit]   = ???
  }

  private val owner                        = new ManualOwner
  private def now[A](signal: Signal[A]): A = signal.observe(using owner).now()

  "the savings figures" - {
    val data = new Fixture(plannedSaving = 200000L)

    "the change of the savings accounts is in the primary currency" in {
      // 500.00 PLN and 100.00 EUR at 4.
      now(data.savingsPeriodChange) shouldBe Money(90000L, PLN)
      now(data.savingsBalance) shouldBe Money(700000L, PLN)
    }

    "the currency effect of the savings accounts is the sum of what the server gives for each" in {
      now(data.savingsCurrencyEffect) shouldBe Money(18000L, PLN)
    }

    "the market result is the change of an investment less what was moved, plus the currency effect" in {
      now(data.investmentPeriodChange) shouldBe Money(100000L, PLN)
      now(data.investmentMovedTotal) shouldBe Money(60000L, PLN)
      now(data.marketResult) shouldBe Money(40000L + 18000L, PLN)
    }

    "saved is the change of the savings accounts plus what was moved into investments, with no market result" in {
      now(data.savedThisPeriod) shouldBe Money(150000L, PLN)
    }
  }

  "the plan" - {
    val data = new Fixture(plannedSaving = 200000L)

    "still to pay and still to receive are sums over the budgets only, each as a positive amount" in {
      now(data.stillToPay) shouldBe Money(120000L, PLN)
      now(data.stillToReceive) shouldBe Money(80000L, PLN)
    }
  }

  "free money" - {
    "keeps back the part of the planned saving that is not yet saved" in {
      val data = new Fixture(plannedSaving = 200000L)
      // Planned 2000.00, saved 1500.00.
      now(data.savingReserve) shouldBe Money(50000L, PLN)
      // 5000.00 + 800.00 to receive - 1200.00 to pay - 500.00 still to save.
      now(data.freeMoney) shouldBe Money(410000L, PLN)
      now(data.dailyBudget) shouldBe Money(41000L, PLN)
    }

    "counts what was saved above the plan as money for the bills still to pay" in {
      val data = new Fixture(plannedSaving = 100000L)
      // Saved 1500.00 against a plan of 1000.00: the 500.00 above the plan waits for bills, and 1200.00 is still to pay.
      now(data.savingReserve) shouldBe Money(-50000L, PLN)
      now(data.freeMoney) shouldBe Money(510000L, PLN)
    }

    "has no saving term when there is no plan" in {
      val data = new Fixture(plannedSaving = 0L)
      now(data.savingReserve) shouldBe Money(0L, PLN)
      now(data.freeMoney) shouldBe Money(460000L, PLN)
    }
  }

  "sumInPrimary" - {
    "converts with the rate, and counts a currency with no rate 1 to 1" in {
      val amounts = List(Money(10000L, PLN), Money(1000L, EUR), Money(500L, Currency.USD))
      DataService.sumInPrimary(amounts, Map(EUR -> 4.0), PLN) shouldBe Money(14500L, PLN)
    }
  }

  "upsertById" - {
    "replaces the item with the same key in its place, and adds a new one at the end" in {
      val list = List("a" -> 1, "b" -> 2)
      DataService.upsertById(list, "a" -> 9)(_._1) shouldBe List("a" -> 9, "b" -> 2)
      DataService.upsertById(list, "c" -> 3)(_._1) shouldBe List("a" -> 1, "b" -> 2, "c" -> 3)
    }
  }
}
