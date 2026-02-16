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
  private val balanceSnapshotsVar: Var[List[BalanceSnapshot]]       = Var(List.empty)
  private val budgetItemsVar: Var[List[BudgetItemDefinition]]       = Var(List.empty)
  private val budgetRecordsVar: Var[List[ExpenseRecord]]            = Var(List.empty)
  private val periodsVar: Var[List[Period]]                         = Var(List.empty)
  private val exchangeRatesVar: Var[Map[Currency, Double]]          = Var(Map.empty)
  private val savingsAccountsVar: Var[List[SavingsAccount]]         = Var(List.empty)
  private val savingsTransactionsVar: Var[List[SavingsTransaction]] = Var(List.empty)
  private val currencySettingsVar: Var[List[CurrencySetting]]       = Var(List.empty)
  private val availableCurrenciesVar: Var[List[(String, String)]]   = Var(List.empty)
  private val oneTimeExpensesVar: Var[List[OneTimeExpense]]         = Var(List.empty)

  // Initialize by fetching all data from individual endpoints
  override def initialize(): Future[Unit] = {
    // Fetch all data in parallel
    val accountsFut         = client.accounts.list()
    val balancesFut         = client.balances.listLatest()
    val budgetItemsFut      = client.budgetItems.list()
    val periodsFut          = client.periods.list()
    val recordsFut          = client.expenseRecords.listCurrent()
    val savingsAccountsFut  = client.savingsAccounts.list()
    val savingsTxnsFut      = client.savingsTransactions.listCurrent()
    val exchangeRatesFut    = client.exchangeRates.getAll()
    val currencySettingsFut = client.currencies.getSettings()
    val oneTimeExpensesFut  = client.oneTimeExpenses.list()

    for {
      accounts         <- accountsFut
      balances         <- balancesFut
      budgetItems      <- budgetItemsFut
      periods          <- periodsFut
      records          <- recordsFut
      savingsAccounts  <- savingsAccountsFut
      savingsTxns      <- savingsTxnsFut
      exchangeRates    <- exchangeRatesFut
      currencySettings <- currencySettingsFut
      oneTimeExpenses  <- oneTimeExpensesFut
    } yield {
      accountsVar.set(accounts)
      balanceSnapshotsVar.set(balances)
      budgetItemsVar.set(budgetItems)
      periodsVar.set(periods)
      budgetRecordsVar.set(records)
      savingsAccountsVar.set(savingsAccounts)
      savingsTransactionsVar.set(savingsTxns)
      // Convert List[ExchangeRate] to Map[Currency, Double] (fromCurrency -> rate)
      exchangeRatesVar.set(exchangeRates.map(r => r.fromCurrency -> r.rateAsDouble).toMap)
      currencySettingsVar.set(currencySettings.currencies)
      availableCurrenciesVar.set(currencySettings.availableCurrencies.map(c => (c.code, c.name)))
      oneTimeExpensesVar.set(oneTimeExpenses)
    }
  }

  // Raw signals
  override def accounts: Signal[List[Account]]                       = accountsVar.signal
  override def balanceSnapshots: Signal[List[BalanceSnapshot]]       = balanceSnapshotsVar.signal
  override def budgetItems: Signal[List[BudgetItemDefinition]]       = budgetItemsVar.signal
  override def budgetRecords: Signal[List[ExpenseRecord]]            = budgetRecordsVar.signal
  override def periods: Signal[List[Period]]                         = periodsVar.signal
  override def exchangeRates: Signal[Map[Currency, Double]]          = exchangeRatesVar.signal
  override def savingsAccounts: Signal[List[SavingsAccount]]         = savingsAccountsVar.signal
  override def savingsTransactions: Signal[List[SavingsTransaction]] = savingsTransactionsVar.signal
  override def currencySettings: Signal[List[CurrencySetting]]       = currencySettingsVar.signal
  override def availableCurrencies: Signal[List[(String, String)]]   = availableCurrenciesVar.signal
  override def oneTimeExpenses: Signal[List[OneTimeExpense]]         = oneTimeExpensesVar.signal

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
    balanceSnapshotsVar.signal
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (snapshots, rates, primary) =>
        sumInPrimary(snapshots.map(_.balance), rates, primary)
      }

  override def totalBalance: Signal[Money] =
    bankAccountBalance
      .combineWith(savingsAccountsVar.signal)
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (bankBalance, savings, rates, primary) =>
        bankBalance + sumInPrimary(savings.map(_.balance), rates, primary)
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
    savingsAccountsVar.signal
      .combineWith(currentPeriodSavingsTransactions)
      .combineWith(exchangeRatesVar.signal)
      .combineWith(primaryCurrency)
      .map { case (accounts, txns, rates, primary) =>
        val remainingAmounts = accounts.flatMap { account =>
          account.plannedMonthly.map { target =>
            val contributions = txns.filter(_.accountId == account.id).map(_.amount).sum
            val remaining     = math.max(0L, target - contributions)
            Money(remaining, account.currency)
          }
        }
        sumInPrimary(remainingAmounts, rates, primary)
      }

  override def periodSavingsTotal: Signal[Money] =
    currentPeriodSavingsTransactions
      .combineWith(savingsAccountsVar.signal)
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

  override def predictedExpenses: Signal[Money] =
    unpaidPlannedExpenses
      .combineWith(scaledEstimatedExpenses)
      .combineWith(remainingSavingsTarget)
      .map { case (unpaid, scaled, savings) => unpaid + scaled + savings }

  override def freeMoney: Signal[Money] =
    bankAccountBalance
      .combineWith(unpaidPlannedExpenses)
      .combineWith(scaledEstimatedExpenses)
      .combineWith(remainingSavingsTarget)
      .combineWith(pendingIncome)
      .map { case (bankBalance, unpaid, scaled, savings, income) => bankBalance - unpaid - scaled - savings + income }

  override def availableNow: Signal[Money] =
    bankAccountBalance
      .combineWith(unpaidPlannedExpenses)
      .map { case (bankBalance, unpaid) => bankBalance - unpaid }

  override def dailyBudget: Signal[Money] =
    freeMoney
      .combineWith(daysRemainingInPeriod)
      .map { case (free, days) => if days > 0 then free / days else Money.zero(free.currency) }

  // Mutation methods
  override def addAccount(name: String, currency: Currency): Future[Unit] = {
    client.accounts.create(CreateAccount(name, currency)).map { response =>
      accountsVar.update(_ :+ response.account)
      balanceSnapshotsVar.update(_ :+ response.balance)
    }
  }

  override def deleteAccount(accountId: AccountId): Future[Unit] = {
    client.accounts.delete(accountId).map { _ =>
      accountsVar.update(_.filterNot(_.id == accountId))
      balanceSnapshotsVar.update(_.filterNot(_.accountId == accountId))
    }
  }

  override def updateAccountBalance(accountId: AccountId, amountCents: Long): Future[Unit] = {
    client.balances.create(CreateBalanceSnapshot(accountId, amountCents)).map { snapshot =>
      balanceSnapshotsVar.update { snapshots =>
        snapshots.filterNot(_.accountId == accountId) :+ snapshot
      }
    }
  }

  override def addBudgetItem(name: String, itemType: BudgetItemType, estimateCents: Long, currency: Currency): Future[Unit] = {
    client.budgetItems.create(CreateBudgetItem(name, itemType, estimateCents, currency)).map { item =>
      budgetItemsVar.update(_ :+ item)
      // If it's a planned expense or income, we need to refetch the records for current period
      if itemType == BudgetItemType.PlannedExpense || itemType == BudgetItemType.PlannedIncome then {
        // The backend will create the expense record; we need to refetch via bootstrap
        // For now, optimistically add a pending record
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
      // Close current period locally
      periodsVar.update { ps =>
        ps.map { p =>
          if p.endDate.isEmpty then p.copy(endDate = Some(Instant.now()))
          else p
        }
      }
      // Add new period
      periodsVar.update(_ :+ newPeriod)
      // Reset current period records (they will be fetched from bootstrap if needed)
      // For now, just create empty records for all planned items
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
      // Clear savings transactions for the new period
      savingsTransactionsVar.set(List.empty)
    }
  }

  override def addSavingsAccount(name: String, currency: Currency, plannedMonthly: Option[Long]): Future[Unit] = {
    client.savingsAccounts.create(CreateSavingsAccount(name, currency, plannedMonthly)).map { account =>
      savingsAccountsVar.update(_ :+ account)
    }
  }

  override def updateSavingsAccount(
      id: SavingsAccountId,
      name: String,
      currency: Currency,
      plannedMonthly: Option[Long],
  ): Future[Unit] = {
    client.savingsAccounts.update(id, UpdateSavingsAccount(name, currency, plannedMonthly)).map { updated =>
      savingsAccountsVar.update(accs => accs.map(a => if a.id == id then updated else a))
    }
  }

  override def updateSavingsAccountBalance(id: SavingsAccountId, newBalance: Long): Future[Unit] = {
    client.savingsAccounts.updateBalance(id, UpdateSavingsAccountBalance(newBalance)).map { updated =>
      savingsAccountsVar.update(accs => accs.map(a => if a.id == id then updated else a))
    }
  }

  override def deleteSavingsAccount(id: SavingsAccountId): Future[Unit] = {
    client.savingsAccounts.delete(id).map { _ =>
      savingsAccountsVar.update(_.filterNot(_.id == id))
      savingsTransactionsVar.update(_.filterNot(_.accountId == id))
    }
  }

  override def addSavingsTransaction(accountId: SavingsAccountId, amount: Long, note: Option[String]): Future[Unit] = {
    client.savingsTransactions.create(CreateSavingsTransaction(accountId, amount, note)).map { response =>
      savingsTransactionsVar.update(_ :+ response.transaction)
      savingsAccountsVar.update(accs => accs.map(a => if a.id == accountId then response.updatedAccount else a))
    }
  }

  override def deleteSavingsTransaction(id: SavingsTransactionId): Future[Unit] = {
    val txnOpt = savingsTransactionsVar.now().find(_.id == id)
    txnOpt match {
      case Some(txn) =>
        client.savingsTransactions.delete(id).map { updatedAccount =>
          savingsTransactionsVar.update(_.filterNot(_.id == id))
          savingsAccountsVar.update(accs => accs.map(a => if a.id == txn.accountId then updatedAccount else a))
        }
      case None      => Future.successful(())
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
      // After refreshing rates, fetch the updated rates
      client.exchangeRates.getAll().map { rates =>
        exchangeRatesVar.set(rates.map(r => r.fromCurrency -> r.rateAsDouble).toMap)
      }
    }
  }
}
