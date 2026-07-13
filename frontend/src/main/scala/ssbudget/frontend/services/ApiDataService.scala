package ssbudget.frontend.services

import com.raquo.laminar.api.L.*
import ssbudget.shared.api.*
import ssbudget.shared.model.*

import java.time.{Instant, LocalDate, ZoneId}
import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContext, Future}

class ApiDataService(client: ApiClient)(implicit ec: ExecutionContext) extends DataService {

  // Mutable state for all entities
  private val accountsVar: Var[List[Account]]                       = Var(List.empty)
  private val budgetItemsVar: Var[List[BudgetItemDefinition]]       = Var(List.empty)
  private val budgetRecordsVar: Var[List[ExpenseRecord]]            = Var(List.empty)
  private val periodsVar: Var[List[Period]]                         = Var(List.empty)
  private val exchangeRatesVar: Var[Map[Currency, Double]]          = Var(Map.empty)
  private val savingsTransactionsVar: Var[List[SavingsTransaction]] = Var(List.empty)
  private val currencySettingsVar: Var[List[CurrencySetting]]       = Var(List.empty)
  private val availableCurrenciesVar: Var[List[(String, String)]]   = Var(List.empty)
  private val oneTimeExpensesVar: Var[List[OneTimeExpense]]         = Var(List.empty)
  private val categorySummariesVar: Var[List[CategorySummary]]      = Var(List.empty)
  private val savingsChangeVar: Var[Money]                          = Var(Money.zero(Currency.PLN)) // actual net savings-balance change this period

  // Initialize by fetching all data from individual endpoints
  override def initialize(): Future[Unit] = {
    val accountsFut         = client.accounts.list()
    val budgetItemsFut      = client.budgetItems.list()
    val periodsFut          = client.periods.list()
    val recordsFut          = client.expenseRecords.listCurrent()
    val savingsTxnsFut      = client.savingsTransactions.listCurrent()
    val exchangeRatesFut    = client.exchangeRates.getAll()
    val currencySettingsFut = client.currencies.getSettings()
    val oneTimeExpensesFut  = client.oneTimeExpenses.list()
    val categorySummsFut    = client.categories.summaries()
    val savingsChangeFut    = client.savings.periodChange()

    for {
      accounts         <- accountsFut
      budgetItems      <- budgetItemsFut
      periods          <- periodsFut
      records          <- recordsFut
      savingsTxns      <- savingsTxnsFut
      exchangeRates    <- exchangeRatesFut
      currencySettings <- currencySettingsFut
      oneTimeExpenses  <- oneTimeExpensesFut
      categorySumms    <- categorySummsFut
      savingsChange    <- savingsChangeFut
    } yield {
      accountsVar.set(accounts)
      budgetItemsVar.set(budgetItems)
      periodsVar.set(periods)
      budgetRecordsVar.set(records)
      savingsTransactionsVar.set(savingsTxns)
      exchangeRatesVar.set(exchangeRates.map(r => r.fromCurrency -> r.rateAsDouble).toMap)
      currencySettingsVar.set(currencySettings.currencies)
      availableCurrenciesVar.set(currencySettings.availableCurrencies.map(c => (c.code, c.name)))
      oneTimeExpensesVar.set(oneTimeExpenses)
      categorySummariesVar.set(categorySumms)
      savingsChangeVar.set(savingsChange)
    }
  }

  // Raw signals
  override def accounts: Signal[List[Account]]                       = accountsVar.signal
  override def spendingAccounts: Signal[List[Account]]               = accountsVar.signal.map(_.filter(_.role == AccountRole.Spending))
  override def savingsAccounts: Signal[List[Account]]                = accountsVar.signal.map(_.filter(_.role == AccountRole.Savings))
  override def budgetItems: Signal[List[BudgetItemDefinition]]       = budgetItemsVar.signal
  override def budgetRecords: Signal[List[ExpenseRecord]]            = budgetRecordsVar.signal
  override def periods: Signal[List[Period]]                         = periodsVar.signal
  override def exchangeRates: Signal[Map[Currency, Double]]          = exchangeRatesVar.signal
  override def savingsTransactions: Signal[List[SavingsTransaction]] = savingsTransactionsVar.signal
  override def currencySettings: Signal[List[CurrencySetting]]       = currencySettingsVar.signal
  override def availableCurrencies: Signal[List[(String, String)]]   = availableCurrenciesVar.signal
  override def oneTimeExpenses: Signal[List[OneTimeExpense]]         = oneTimeExpensesVar.signal
  override def categorySummaries: Signal[List[CategorySummary]]      = categorySummariesVar.signal

  override def enabledCurrencies: Signal[List[Currency]] =
    currencySettingsVar.signal.map(_.map(_.code))

  override def primaryCurrency: Signal[Currency] =
    currencySettingsVar.signal.map(_.find(_.isPrimary).map(_.code).getOrElse(Currency.PLN))

  // Derived signals
  override def currentPeriod: Signal[Option[Period]] =
    periodsVar.signal.map(_.find(_.endDate.isEmpty))

  override def plannedExpenses: Signal[List[BudgetItemDefinition]] =
    budgetItemsVar.signal.map(_.filter(_.itemType == BudgetItemType.PlannedExpense))

  override def estimatedExpenses: Signal[List[BudgetItemDefinition]] =
    budgetItemsVar.signal.map(_.filter(_.itemType == BudgetItemType.EstimatedExpense))

  override def plannedIncomes: Signal[List[BudgetItemDefinition]] =
    budgetItemsVar.signal.map(_.filter(_.itemType == BudgetItemType.PlannedIncome))

  override def currentPeriodRecords: Signal[List[ExpenseRecord]] =
    Signal
      .combine(budgetRecordsVar.signal, currentPeriod)
      .map { case (records, periodOpt) =>
        periodOpt.fold(List.empty[ExpenseRecord])(period => records.filter(_.periodId == period.id))
      }

  override def currentPeriodSavingsTransactions: Signal[List[SavingsTransaction]] =
    Signal
      .combine(savingsTransactionsVar.signal, currentPeriod)
      .map { case (txns, periodOpt) =>
        periodOpt.fold(List.empty[SavingsTransaction])(period => txns.filter(_.periodId == period.id))
      }

  // Helper to sum Money in various currencies into primary currency
  private def sumInPrimary(amounts: Seq[Money], rates: Map[Currency, Double], primary: Currency): Money = {
    val total = amounts.foldLeft(0L) { (acc, money) =>
      val converted =
        if money.currency == primary then money.amountCents
        else rates.get(money.currency).map(rate => (money.amountCents * rate).toLong).getOrElse(money.amountCents)
      acc + converted
    }
    Money(total, primary)
  }

  override def bankAccountBalance: Signal[Money] =
    spendingAccounts
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (accounts, rates, primary) =>
        sumInPrimary(accounts.map(_.balance), rates, primary)
      }

  override def totalBalance: Signal[Money] =
    accountsVar.signal
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (accounts, rates, primary) =>
        sumInPrimary(accounts.map(_.balance), rates, primary)
      }

  override def daysRemainingInPeriod: Signal[Int] =
    currentPeriod.map {
      case Some(_) =>
        val today     = LocalDate.now(ZoneId.of("UTC"))
        val day25     = today.withDayOfMonth(25)
        val periodEnd = if today.getDayOfMonth < 25 then day25 else day25.plusMonths(1)
        val daysLeft  = ChronoUnit.DAYS.between(today, periodEnd).toInt
        math.max(1, daysLeft)
      case None    => 0
    }

  override def periodElapsedFraction: Signal[Double] =
    currentPeriod.map {
      case Some(p) =>
        val zone    = ZoneId.of("UTC")
        val start   = p.startDate.atZone(zone).toLocalDate
        val today   = LocalDate.now(zone)
        val day25   = today.withDayOfMonth(25)
        val end     = if today.getDayOfMonth < 25 then day25 else day25.plusMonths(1)
        val total   = ChronoUnit.DAYS.between(start, end).toDouble
        val elapsed = ChronoUnit.DAYS.between(start, today).toDouble
        if total <= 0 then 1.0 else math.max(0.0, math.min(1.0, elapsed / total))
      case None    => 0.0
    }

  /** Budgeted categories only (any budget type set), in display order (by name). */
  override def budgetedCategories: Signal[List[CategorySummary]] =
    categorySummariesVar.signal.map(_.filter(_.category.budgetType.isDefined).sortBy(_.category.name))

  /** Remaining category-budget spend expected before the next paycheck, per the category's budget type (see [[CategoryBudgetType.remaining]]), summed
    * in the primary currency. Folded into predicted expenses.
    */
  override def categoryBudgetsRemaining: Signal[Money] =
    categorySummariesVar.signal
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .combineWith(periodElapsedFraction)
      .map { case (summaries, rates, primary, elapsed) =>
        val amounts = summaries.flatMap { s =>
          s.category.budgetType.map(bt => Money(CategoryBudgetType.remaining(bt, s.avgMonthlyCents, s.currentPeriodSpentCents, elapsed), s.currency))
        }
        sumInPrimary(amounts, rates, primary)
      }

  override def unpaidPlannedExpenses: Signal[Money] =
    plannedExpenses
      .combineWith(currentPeriodRecords)
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (planned, records, rates, primary) =>
        val unpaidAmounts = planned.flatMap { exp =>
          val isPaid = records.exists(r => r.expenseDefId == exp.id && r.paidAmount.isDefined)
          if isPaid then None else exp.estimateMoney
        }
        sumInPrimary(unpaidAmounts, rates, primary)
      }

  override def scaledEstimatedExpenses: Signal[Money] =
    estimatedExpenses
      .combineWith(daysRemainingInPeriod)
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (estimated, daysRemaining, rates, primary) =>
        val scaleFactor   = daysRemaining.toDouble / 30.0
        val scaledAmounts = estimated.flatMap { exp =>
          exp.estimateMoney.map(_ * scaleFactor)
        }
        sumInPrimary(scaledAmounts, rates, primary)
      }

  override def remainingSavingsTarget: Signal[Money] =
    savingsAccounts
      .combineWith(currentPeriodSavingsTransactions)
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (accounts, txns, rates, primary) =>
        val remainingAmounts = accounts.flatMap { account =>
          account.savingsTarget.map { target =>
            val contributions = txns.filter(_.accountId == account.id).map(_.amount).sum
            val remaining     = math.max(0L, target - contributions)
            Money(remaining, account.currency)
          }
        }
        sumInPrimary(remainingAmounts, rates, primary)
      }

  override def periodSavingsTotal: Signal[Money] =
    currentPeriodSavingsTransactions
      .combineWith(savingsAccounts)
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (txns, accounts, rates, primary) =>
        val accountCurrency = accounts.map(a => a.id -> a.currency).toMap
        val amounts         = txns.map { txn =>
          val currency = accountCurrency.getOrElse(txn.accountId, primary)
          Money(txn.amount, currency)
        }
        sumInPrimary(amounts, rates, primary)
      }

  override def periodOneTimeExpensesTotal: Signal[Money] =
    oneTimeExpensesVar.signal
      .combineWith(currentPeriod)
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (expenses, periodOpt, rates, primary) =>
        val zone           = ZoneId.of("UTC")
        val periodExpenses = periodOpt.fold(List.empty[OneTimeExpense]) { period =>
          val periodStartDay = period.startDate.atZone(zone).toLocalDate
          expenses.filter { e =>
            val expDay = e.date.atZone(zone).toLocalDate
            !expDay.isBefore(periodStartDay) &&
            period.endDate.forall(end => expDay.isBefore(end.atZone(zone).toLocalDate))
          }
        }
        val amounts        = periodExpenses.map(e => Money(e.amountCents, e.currency))
        sumInPrimary(amounts, rates, primary)
      }

  override def pendingIncome: Signal[Money] =
    plannedIncomes
      .combineWith(currentPeriodRecords)
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (incomes, records, rates, primary) =>
        val pendingAmounts = incomes.flatMap { inc =>
          val isReceived = records.exists(r => r.expenseDefId == inc.id && r.paidAmount.isDefined)
          if isReceived then None else inc.estimateMoney
        }
        sumInPrimary(pendingAmounts, rates, primary)
      }

  override def savingsPeriodChange: Signal[Money] = savingsChangeVar.signal

  override def predictedExpenses: Signal[Money] =
    unpaidPlannedExpenses
      .combineWith(scaledEstimatedExpenses)
      .combineWith(categoryBudgetsRemaining)
      .map { case (unpaid, scaled, catBudgets) => unpaid + scaled + catBudgets }

  // Savings is deliberately NOT subtracted here: moving money to savings already lowers the (spending-only) bankAccountBalance, so reserving it
  // again would double-count. Actual savings movement is surfaced separately via `savingsPeriodChange` (informational).
  override def freeMoney: Signal[Money] =
    bankAccountBalance
      .combineWith(unpaidPlannedExpenses)
      .combineWith(scaledEstimatedExpenses)
      .combineWith(categoryBudgetsRemaining)
      .combineWith(pendingIncome)
      .map { case (bankBalance, unpaid, scaled, catBudgets, income) => bankBalance - unpaid - scaled - catBudgets + income }

  override def availableNow: Signal[Money] =
    bankAccountBalance
      .combineWith(unpaidPlannedExpenses)
      .map { case (bankBalance, unpaid) => bankBalance - unpaid }

  override def dailyBudget: Signal[Money] =
    freeMoney
      .combineWith(daysRemainingInPeriod)
      .map { case (free, days) => if days > 0 then free / days else Money.zero(free.currency) }

  // Mutation methods
  private def upsertAccount(account: Account): Unit =
    accountsVar.update(DataService.upsertById(_, account)(_.id))

  override def addAccount(name: String, currency: Currency): Future[Unit] =
    client.accounts.create(CreateAccount(name, currency, AccountRole.Spending, None)).map(upsertAccount)

  override def deleteAccount(accountId: AccountId): Future[Unit] =
    client.accounts.delete(accountId).map { _ =>
      accountsVar.update(_.filterNot(_.id == accountId))
      savingsTransactionsVar.update(_.filterNot(_.accountId == accountId))
    }

  override def updateAccountBalance(accountId: AccountId, amountCents: Long): Future[Unit] =
    client.accounts.updateBalance(accountId, UpdateAccountBalance(amountCents)).map(upsertAccount)

  override def addBudgetItem(name: String, itemType: BudgetItemType, estimateCents: Long, currency: Currency): Future[Unit] = {
    client.budgetItems.create(CreateBudgetItem(name, itemType, estimateCents, currency)).map { item =>
      budgetItemsVar.update(_ :+ item)
      if itemType == BudgetItemType.PlannedExpense || itemType == BudgetItemType.PlannedIncome then {
        getCurrentPeriod.foreach { period =>
          budgetRecordsVar.update { records =>
            records :+ ExpenseRecord(
              ExpenseRecordId(s"temp-${System.currentTimeMillis()}"),
              period.id,
              item.id,
              None,
              None,
            )
          }
        }
      }
    }
  }

  override def updateBudgetItemEstimate(itemId: ExpenseDefId, newEstimateCents: Long, currency: Currency): Future[Unit] = {
    val current = budgetItemsVar.now().find(_.id == itemId)
    current match {
      case Some(item) =>
        client.budgetItems.update(itemId, UpdateBudgetItem(item.name, item.itemType, newEstimateCents, currency)).map { updated =>
          budgetItemsVar.update(items => items.map(i => if i.id == itemId then updated else i))
        }
      case None       => Future.failed(new Exception(s"Budget item not found: $itemId"))
    }
  }

  override def deleteBudgetItem(itemId: ExpenseDefId): Future[Unit] = {
    client.budgetItems.delete(itemId).map { _ =>
      budgetItemsVar.update(_.filterNot(_.id == itemId))
      budgetRecordsVar.update(_.filterNot(_.expenseDefId == itemId))
    }
  }

  override def markBudgetItemAsPaid(itemId: ExpenseDefId, amountCents: Long): Future[Unit] = {
    client.expenseRecords.pay(itemId, PayBudgetItem(amountCents)).map { record =>
      budgetRecordsVar.update { records =>
        records.map(r => if r.expenseDefId == itemId && r.periodId == record.periodId then record else r)
      }
    }
  }

  override def unmarkBudgetItemAsPaid(itemId: ExpenseDefId): Future[Unit] = {
    client.expenseRecords.unpay(itemId).map { record =>
      budgetRecordsVar.update { records =>
        records.map(r => if r.expenseDefId == itemId && r.periodId == record.periodId then record else r)
      }
    }
  }

  override def startNewPeriod(): Future[Unit] = {
    client.periods.startNew().map { newPeriod =>
      periodsVar.update { ps =>
        ps.map { p =>
          if p.endDate.isEmpty then p.copy(endDate = Some(Instant.now()))
          else p
        }
      }
      periodsVar.update(_ :+ newPeriod)
      val plannedItems =
        budgetItemsVar.now().filter(item => item.itemType == BudgetItemType.PlannedExpense || item.itemType == BudgetItemType.PlannedIncome)
      budgetRecordsVar.set(
        plannedItems.map { item =>
          ExpenseRecord(
            ExpenseRecordId(s"rec-${System.currentTimeMillis()}-${item.id.value}"),
            newPeriod.id,
            item.id,
            None,
            None,
          )
        },
      )
      savingsTransactionsVar.set(List.empty)
    }
  }

  override def addSavingsAccount(name: String, currency: Currency, savingsTarget: Option[Long]): Future[Unit] =
    client.accounts.create(CreateAccount(name, currency, AccountRole.Savings, savingsTarget)).map(upsertAccount)

  override def updateAccount(id: AccountId, name: String, currency: Currency, savingsTarget: Option[Long]): Future[Unit] =
    client.accounts.update(id, UpdateAccount(name, currency, savingsTarget)).map(upsertAccount)

  override def addSavingsTransaction(accountId: AccountId, amount: Long, note: Option[String]): Future[Unit] = {
    client.savingsTransactions.create(CreateSavingsTransaction(accountId, amount, note)).map { txn =>
      savingsTransactionsVar.update(_ :+ txn)
    }
  }

  override def deleteSavingsTransaction(id: SavingsTransactionId): Future[Unit] = {
    client.savingsTransactions.delete(id).map { _ =>
      savingsTransactionsVar.update(_.filterNot(_.id == id))
    }
  }

  // One-time expenses
  override def addOneTimeExpense(name: String, amountCents: Long, currency: Currency, date: Option[Instant]): Future[Unit] = {
    client.oneTimeExpenses.create(CreateOneTimeExpense(name, amountCents, currency, date)).map { expense =>
      oneTimeExpensesVar.update(_ :+ expense)
    }
  }

  override def updateOneTimeExpense(id: OneTimeExpenseId, name: String, amountCents: Long, currency: Currency, date: Instant): Future[Unit] = {
    client.oneTimeExpenses.update(id, UpdateOneTimeExpense(name, amountCents, currency, date)).map { updated =>
      oneTimeExpensesVar.update(exps => exps.map(e => if e.id == id then updated else e))
    }
  }

  override def deleteOneTimeExpense(id: OneTimeExpenseId): Future[Unit] = {
    client.oneTimeExpenses.delete(id).map { _ =>
      oneTimeExpensesVar.update(_.filterNot(_.id == id))
    }
  }

  private def getCurrentPeriod: Option[Period] =
    periodsVar.now().find(_.endDate.isEmpty)

  // Currency settings mutations
  override def enableCurrency(code: String): Future[Unit] = {
    client.currencies.enable(code).map { setting =>
      currencySettingsVar.update(_ :+ setting)
    }
  }

  override def disableCurrency(code: String): Future[Unit] = {
    client.currencies.disable(code).map { _ =>
      currencySettingsVar.update(_.filterNot(_.code.code == code))
    }
  }

  override def setPrimaryCurrency(code: String): Future[Unit] = {
    client.currencies.setPrimary(code).map { _ =>
      currencySettingsVar.update { settings =>
        settings.map { s =>
          if s.code.code == code then s.copy(isPrimary = true)
          else s.copy(isPrimary = false)
        }
      }
    }
  }

  override def refreshExchangeRates(): Future[Unit] = {
    client.currencies.refreshRates().flatMap { response =>
      client.exchangeRates.getAll().map { rates =>
        exchangeRatesVar.set(rates.map(r => r.fromCurrency -> r.rateAsDouble).toMap)
      }
    }
  }
}
