package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import ssbudget.frontend.components.{Badges, Loading}
import ssbudget.frontend.services.{ApiClient, DataService}
import ssbudget.frontend.util.{Formatting, MoneyFormatter}
import ssbudget.shared.model.*

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

object DashboardPage {

  private val dataService = DataService.instance
  private val api         = new ApiClient()

  private val isEditingBalances = Var(false)
  private val editedBalances    = Var(Map.empty[AccountId, Long])
  private val copyButtonText    = Var("Copy Summary")
  private val syncingBanks      = Var(false)
  private val syncMessage       = Var(Option.empty[(Boolean, String)]) // result of the last sync: (ok, message)

  /** When the balance was last driven by a bank sync (max over externally-sourced accounts). */
  private val lastSyncedAt: Signal[Option[Instant]] =
    dataService.accounts.map(_.filterNot(_.isManual).flatMap(_.balanceUpdatedAt).maxOption)

  private def syncBanks(): Unit = {
    syncingBanks.set(true)
    syncMessage.set(None)
    // One call syncs balances AND imports new transactions across all connections (resilient to a single bank failing).
    api.banking
      .syncAll()
      .flatMap(r => dataService.initialize().map(_ => r))
      .onComplete {
        case Success(r)  =>
          syncingBanks.set(false)
          val base = s"Synced ${r.synced} bank(s), imported ${r.imported} new transaction(s)"
          syncMessage.set(Some(if r.errors.isEmpty then (true, base) else (false, s"$base — issues: ${r.errors.mkString("; ")}")))
        case Failure(ex) =>
          syncingBanks.set(false)
          syncMessage.set(Some((false, s"Sync failed: ${ex.getMessage}")))
      }
  }

  def apply(): HtmlElement = {
    div(
      cls := "container-fluid mt-3",
      // Refresh on each visit so changes made elsewhere (e.g. linking a bank account) are reflected without a manual page reload.
      onMountCallback(_ => { dataService.initialize(); () }),
      div(
        cls := "d-flex justify-content-between align-items-center mb-3",
        h4(cls := "mb-0", "Dashboard"),
        div(
          cls  := "btn-group btn-group-sm",
          button(
            cls := "btn btn-outline-secondary",
            child.text <-- copyButtonText.signal,
            onClick --> { _ => copySummaryToClipboard() },
          ),
          button(
            cls := "btn btn-outline-success",
            "WhatsApp",
            onClick --> { _ => shareViaWhatsApp() },
          ),
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
            div(cls := "fs-4 fw-bold font-monospace", MoneyFormatter.formatChild(dataService.bankAccountBalance)),
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
          accountingRow("Balance", dataService.bankAccountBalance, positive = true, bold = true),
          accountingRow("+ Pending Income", dataService.pendingIncome, positive = true),
          accountingRow("- Planned Expenses", dataService.unpaidPlannedExpenses, positive = false),
          accountingRow("- Estimated Expenses", dataService.scaledEstimatedExpenses, positive = false),
          accountingRow("- Remaining Savings", dataService.remainingSavingsTarget, positive = false),
          hr(cls := "my-1"),
          accountingRow("= Free Money", dataService.freeMoney, positive = true, bold = true),
          hr(cls := "my-1"),
          accountingRow("Saved", dataService.periodSavingsTotal, positive = true),
          accountingRow("One-Time Expenses", dataService.periodOneTimeExpensesTotal, positive = false),
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
        div(
          cls := "d-flex align-items-baseline gap-2",
          span("Accounts"),
          child.maybe <-- lastSyncedAt.map(_.map(t => small(cls := "text-muted", s"synced ${Formatting.formatDateTime(t)}"))),
          child.maybe <-- syncMessage.signal.map(_.map { case (ok, msg) => small(cls := (if ok then "text-success" else "text-danger"), msg) }),
        ),
        div(
          cls := "btn-group btn-group-sm",
          button(
            cls   := "btn btn-outline-primary py-0",
            title := "Sync balances and import new transactions from all connected banks",
            disabled <-- syncingBanks.signal,
            child <-- syncingBanks.signal.map { syncing =>
              if syncing then span(span(cls := "spinner-border spinner-border-sm me-1"), "Syncing...")
              else span("Sync banks")
            },
            onClick --> { _ => syncBanks() },
          ),
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
      ),
      div(
        cls := "card-body p-0",
        table(
          cls := "table table-sm table-hover mb-0",
          thead(tr(th("Account"), th(cls := "text-end", "Balance"))),
          tbody(
            // Spending accounts
            children <-- dataService.spendingAccounts
              .combineWith(isEditingBalances.signal)
              .map { case (accounts, isEditing) =>
                accounts.map(account => accountQuickRow(account, isEditing))
              },
            // Separator
            tr(cls := "table-secondary", td(colSpan := 2, cls := "py-1 small text-muted", "— Savings —")),
            // Savings accounts
            children <-- dataService.savingsAccounts
              .combineWith(isEditingBalances.signal)
              .map { case (accounts, isEditing) =>
                accounts.map(account => accountQuickRow(account, isEditing))
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

  private def accountQuickRow(account: Account, isEditing: Boolean): HtmlElement = {
    val nameCell    = td(account.name, Badges.source(account.balanceSource))
    val balanceView = MoneyFormatter.format(account.balanceCents, account.currency)

    if isEditing && !account.isManual then
    // Bank/card-driven balance: not manually editable, updated via Sync banks.
    tr(nameCell, td(cls := "text-end font-monospace text-muted", balanceView))
    else if isEditing then tr(
      cls := "table-info",
      nameCell,
      td(
        div(
          cls := "input-group input-group-sm",
          input(
            cls          := "form-control form-control-sm text-end",
            tpe          := "number",
            stepAttr     := "0.01",
            defaultValue := (account.balanceCents / 100.0).toString,
            onInput.mapToValue --> { v => v.toDoubleOption.foreach(d => editedBalances.update(_.updated(account.id, (d * 100).toLong))) },
          ),
          span(cls       := "input-group-text py-0", account.currency.code),
        ),
      ),
    )
    else tr(nameCell, td(cls := "text-end font-monospace", balanceView))
  }

  private def startEditingBalances(): Unit = {
    import com.raquo.airstream.ownership.OneTimeOwner
    given owner: OneTimeOwner = new OneTimeOwner(() => ())

    val accounts = dataService.accounts.observe.now()
    editedBalances.set(accounts.map(acc => acc.id -> acc.balanceCents).toMap)
    isEditingBalances.set(true)
  }

  private def saveAllBalances(): Future[Unit] = {
    import com.raquo.airstream.ownership.OneTimeOwner
    given owner: OneTimeOwner = new OneTimeOwner(() => ())

    val accounts = dataService.accounts.observe.now()
    val edited   = editedBalances.now()
    // Only manual-source accounts are editable; bank/card-group balances are driven by Sync.
    val futures  =
      accounts
        .filter(_.isManual)
        .flatMap(acc => edited.get(acc.id).map(amount => dataService.updateAccountBalance(acc.id, amount)))

    Future.sequence(futures).map { _ =>
      isEditingBalances.set(false)
      editedBalances.set(Map.empty)
    }
  }

  private def buildSummaryText(): String = {
    import com.raquo.airstream.ownership.OneTimeOwner
    given owner: OneTimeOwner = new OneTimeOwner(() => ())

    val balance       = dataService.bankAccountBalance.observe.now()
    val availableNow  = dataService.availableNow.observe.now()
    val freeMoney     = dataService.freeMoney.observe.now()
    val dailyBudget   = dataService.dailyBudget.observe.now()
    val daysRemaining = dataService.daysRemainingInPeriod.observe.now()

    val dateStr = DateTimeFormatter.ofPattern("MMM d").format(Instant.now().atZone(ZoneOffset.UTC))
    s"""Budget Update ($dateStr)
       |Balance: ${balance.formatted}
       |Available: ${availableNow.formatted}
       |Free: ${freeMoney.formatted}
       |Daily: ${dailyBudget.formatted} ($daysRemaining days left)""".stripMargin
  }

  private def copySummaryToClipboard(): Unit = {
    val summary = buildSummaryText()
    dom.window.navigator.clipboard
      .writeText(summary)
      .toFuture
      .foreach { _ =>
        copyButtonText.set("Copied!")
        dom.window.setTimeout(() => copyButtonText.set("Copy Summary"), 2000)
      }(scala.concurrent.ExecutionContext.global)
  }

  private def shareViaWhatsApp(): Unit = {
    import scala.scalajs.js.URIUtils
    val summary    = buildSummaryText()
    val encodedMsg = URIUtils.encodeURIComponent(summary)
    val url        = s"https://wa.me/?text=$encodedMsg"
    dom.window.open(url, "_blank")
  }
}
