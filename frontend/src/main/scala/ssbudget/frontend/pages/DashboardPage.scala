package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import ssbudget.frontend.components.Loading
import ssbudget.frontend.services.DataService
import ssbudget.frontend.util.{Formatting, MoneyFormatter}
import ssbudget.shared.model.*

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

object DashboardPage {

  private val dataService = DataService.instance

  private val isEditingBalances     = Var(false)
  private val editedBalances        = Var(Map.empty[AccountId, Long])
  private val editedSavingsBalances = Var(Map.empty[SavingsAccountId, Long])
  private val copyButtonText        = Var("Copy Summary")

  def apply(): HtmlElement = {
    div(
      cls := "container-fluid mt-3",
      div(
        cls := "d-flex justify-content-between align-items-center mb-3",
        h4(cls := "mb-0", "Dashboard"),
        button(
          cls  := "btn btn-sm btn-outline-secondary",
          child.text <-- copyButtonText.signal,
          onClick --> { _ => copySummaryToClipboard() },
        ),
      ),
      div(
        cls := "row g-3",
        div(
          cls   := "col-lg-5",
          summaryPanel(),
          periodCard(),
        ),
        div(cls := "col-lg-7", accountsQuickView()),
      ),
    )
  }

  private def summaryPanel(): HtmlElement = {
    div(
      cls := "card mb-3",
      div(
        cls := "card-body py-2",
        // Quick summary row
        div(
          cls  := "row align-items-center mb-2",
          div(
            cls   := "col-auto",
            div(cls := "text-muted small", "BALANCE"),
            div(cls := "fs-4 fw-bold font-monospace", MoneyFormatter.formatChild(dataService.totalBalance)),
          ),
          div(cls := "col-auto fs-4 text-muted", "→"),
          div(
            cls   := "col-auto",
            div(cls := "text-muted small", "FREE"),
            div(cls := "fs-5 font-monospace text-success fw-bold", MoneyFormatter.formatChild(dataService.freeMoney)),
          ),
          div(cls := "col-auto fs-4 text-muted", "÷"),
          div(
            cls   := "col-auto",
            div(
              cls   := "text-muted small",
              child.text <-- dataService.daysRemainingInPeriod.map(d => s"$d DAYS"),
            ),
            div(cls := "fs-5 font-monospace text-primary fw-bold", MoneyFormatter.formatChild(dataService.dailyBudget)),
          ),
        ),
        // Accounting breakdown
        hr(cls := "my-2"),
        div(
          cls  := "font-monospace small",
          accountingRow("Balance", dataService.totalBalance, positive = true, bold = true),
          accountingRow("+ Pending Income", dataService.pendingIncome, positive = true),
          accountingRow("- Planned Expenses", dataService.unpaidPlannedExpenses, positive = false),
          accountingRow("- Estimated Expenses", dataService.scaledEstimatedExpenses, positive = false),
          accountingRow("- Remaining Savings", dataService.remainingSavingsTarget, positive = false),
          hr(cls := "my-1"),
          accountingRow("= Free Money", dataService.freeMoney, positive = true, bold = true),
        ),
      ),
    )
  }

  private def accountingRow(label: String, amount: Signal[Money], positive: Boolean, bold: Boolean = false): HtmlElement = {
    val textCls = if positive then "text-success" else "text-danger"
    val fontCls = if bold then "fw-bold" else ""
    div(
      cls := s"d-flex justify-content-between $fontCls",
      span(label),
      span(cls := textCls, MoneyFormatter.formatChild(amount)),
    )
  }

  private def periodCard(): HtmlElement = {
    div(
      cls := "card",
      div(cls := "card-header py-2", "Current Period"),
      div(
        cls   := "card-body py-2",
        child <-- dataService.currentPeriod.map {
          case Some(period) =>
            div(
              div(
                cls       := "d-flex justify-content-between mb-2",
                span(s"Started: ${Formatting.formatDate(period.startDate)}"),
                span(cls := "text-muted", child.text <-- dataService.daysRemainingInPeriod.map(d => s"$d days remaining")),
              ),
              div(
                cls       := "progress",
                styleAttr := "height: 8px",
                div(
                  cls  := "progress-bar",
                  role := "progressbar",
                  styleAttr <-- dataService.daysRemainingInPeriod.map { _ =>
                    s"width: ${Formatting.periodProgress(period.startDate)}%"
                  },
                ),
              ),
            )
          case None         => div(cls := "text-muted", "No active period")
        },
      ),
    )
  }

  private def accountsQuickView(): HtmlElement = {
    div(
      cls := "card",
      div(
        cls := "card-header py-2 d-flex justify-content-between align-items-center",
        span("Accounts"),
        child <-- isEditingBalances.signal.map { isEditing =>
          if isEditing then div(
            cls   := "btn-group btn-group-sm",
            Loading.actionButton(
              "Save All",
              () => saveAllBalances(),
              "btn btn-success btn-sm py-0",
            ),
            button(
              cls := "btn btn-secondary btn-sm py-0",
              "Cancel",
              onClick --> { _ =>
                isEditingBalances.set(false)
                editedBalances.set(Map.empty)
                editedSavingsBalances.set(Map.empty)
              },
            ),
          )
          else
            button(
              cls := "btn btn-sm btn-outline-primary py-0",
              "Edit Balances",
              onClick --> { _ => startEditingBalances() },
            )
        },
      ),
      div(
        cls := "card-body p-0",
        table(
          cls := "table table-sm table-hover mb-0",
          thead(tr(th("Account"), th(cls := "text-end", "Balance"))),
          tbody(
            // Bank accounts
            children <-- dataService.accounts
              .combineWith(dataService.balanceSnapshots)
              .combineWith(isEditingBalances.signal)
              .map { case (accounts, snapshots, isEditing) =>
                accounts.map(account => bankAccountQuickRow(account, snapshots.find(_.accountId == account.id), isEditing))
              },
            // Separator
            tr(cls := "table-secondary", td(colSpan := 2, cls := "py-1 small text-muted", "— Savings —")),
            // Savings accounts
            children <-- dataService.savingsAccounts
              .combineWith(isEditingBalances.signal)
              .map { case (accounts, isEditing) =>
                accounts.map(account => savingsAccountQuickRow(account, isEditing))
              },
          ),
        ),
      ),
      div(
        cls := "card-footer py-2 d-flex justify-content-between",
        span(cls := "fw-bold", "Total"),
        span(cls := "font-monospace fw-bold", MoneyFormatter.formatChild(dataService.totalBalance)),
      ),
    )
  }

  private def bankAccountQuickRow(account: Account, balanceOpt: Option[BalanceSnapshot], isEditing: Boolean): HtmlElement = {
    val currentAmount = balanceOpt.map(_.amount).getOrElse(0L)

    if isEditing then tr(
      cls := "table-info",
      td(account.name),
      td(
        div(
          cls := "input-group input-group-sm",
          input(
            cls          := "form-control form-control-sm text-end",
            tpe          := "number",
            stepAttr     := "0.01",
            defaultValue := (currentAmount / 100.0).toString,
            onInput.mapToValue --> { v => v.toDoubleOption.foreach(d => editedBalances.update(_.updated(account.id, (d * 100).toLong))) },
          ),
          span(cls       := "input-group-text py-0", account.currency.code),
        ),
      ),
    )
    else
      tr(
        td(account.name),
        td(cls := "text-end font-monospace", balanceOpt.fold[HtmlElement](span("-"))(b => MoneyFormatter.format(b.amount, b.currency))),
      )
  }

  private def savingsAccountQuickRow(account: SavingsAccount, isEditing: Boolean): HtmlElement = {
    if isEditing then tr(
      cls := "table-info",
      td(account.name),
      td(
        div(
          cls := "input-group input-group-sm",
          input(
            cls          := "form-control form-control-sm text-end",
            tpe          := "number",
            stepAttr     := "0.01",
            defaultValue := (account.currentBalance / 100.0).toString,
            onInput.mapToValue --> { v => v.toDoubleOption.foreach(d => editedSavingsBalances.update(_.updated(account.id, (d * 100).toLong))) },
          ),
          span(cls       := "input-group-text py-0", account.currency.code),
        ),
      ),
    )
    else tr(td(account.name), td(cls := "text-end font-monospace", MoneyFormatter.format(account.currentBalance, account.currency)))
  }

  private def startEditingBalances(): Unit = {
    import com.raquo.airstream.ownership.OneTimeOwner
    given owner: OneTimeOwner = new OneTimeOwner(() => ())

    val accounts        = dataService.accounts.observe.now()
    val snapshots       = dataService.balanceSnapshots.observe.now()
    val savingsAccounts = dataService.savingsAccounts.observe.now()

    val initialBankBalances    = accounts.map(acc => acc.id -> snapshots.find(_.accountId == acc.id).map(_.amount).getOrElse(0L)).toMap
    val initialSavingsBalances = savingsAccounts.map(acc => acc.id -> acc.currentBalance).toMap

    editedBalances.set(initialBankBalances)
    editedSavingsBalances.set(initialSavingsBalances)
    isEditingBalances.set(true)
  }

  private def saveAllBalances(): Future[Unit] = {
    import com.raquo.airstream.ownership.OneTimeOwner
    given owner: OneTimeOwner = new OneTimeOwner(() => ())

    val accounts    = dataService.accounts.observe.now()
    val edited      = editedBalances.now()
    val bankFutures = accounts.flatMap(acc => edited.get(acc.id).map(amount => dataService.updateAccountBalance(acc.id, amount)))

    val savingsAccounts = dataService.savingsAccounts.observe.now()
    val editedSavings   = editedSavingsBalances.now()
    val savingsFutures  =
      savingsAccounts.flatMap(acc => editedSavings.get(acc.id).map(amount => dataService.updateSavingsAccountBalance(acc.id, amount)))

    Future.sequence(bankFutures ++ savingsFutures).map { _ =>
      isEditingBalances.set(false)
      editedBalances.set(Map.empty)
      editedSavingsBalances.set(Map.empty)
    }
  }

  private def copySummaryToClipboard(): Unit = {
    import com.raquo.airstream.ownership.OneTimeOwner
    given owner: OneTimeOwner = new OneTimeOwner(() => ())

    val balance       = dataService.totalBalance.observe.now()
    val availableNow  = dataService.availableNow.observe.now()
    val freeMoney     = dataService.freeMoney.observe.now()
    val dailyBudget   = dataService.dailyBudget.observe.now()
    val daysRemaining = dataService.daysRemainingInPeriod.observe.now()

    val dateStr = DateTimeFormatter.ofPattern("MMM d").format(Instant.now().atZone(ZoneOffset.UTC))
    val summary =
      s"""Budget Update ($dateStr)
         |Balance: ${balance.formatted}
         |Available: ${availableNow.formatted}
         |Free: ${freeMoney.formatted}
         |Daily: ${dailyBudget.formatted} ($daysRemaining days left)""".stripMargin

    dom.window.navigator.clipboard
      .writeText(summary)
      .toFuture
      .foreach { _ =>
        copyButtonText.set("Copied!")
        dom.window.setTimeout(() => copyButtonText.set("Copy Summary"), 2000)
      }(scala.concurrent.ExecutionContext.global)
  }
}
