package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import ssbudget.frontend.components.{Badges, Loading, PlanCard, TriageCard}
import ssbudget.frontend.services.{ApiClient, DataService}
import ssbudget.frontend.util.{Formatting, MoneyFormatter}
import ssbudget.frontend.{Page, Router}
import ssbudget.shared.model.*

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/** The one working surface: the free-money headline, the arithmetic behind it, the balances it starts from, and the plan of everything still expected
  * to move. The period is deliberately reduced to a hairline strip: it frames the numbers but is never the thing you came here to read (its detail
  * and history live on the Periods page).
  */
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

  /** Sync balances + import transactions across all connections as a background job, then poll it to completion (see the Banking page's Import
    * activity card for full history/errors). On finish, reload dashboard data.
    */
  private def syncBanks(): Unit = {
    syncingBanks.set(true)
    syncMessage.set(None)
    api.banking.syncAll().onComplete {
      case Success(job) => pollSyncJob(job.id)
      case Failure(ex)  => syncingBanks.set(false); syncMessage.set(Some((false, s"Couldn't start sync: ${ex.getMessage}")))
    }
  }

  private def pollSyncJob(id: ImportJobId): Unit =
    api.jobs.get(id).onComplete {
      case Success(job) if job.status == ImportJobStatus.Running =>
        dom.window.setTimeout(() => pollSyncJob(id), 2000d) // keep polling every 2s while it runs
        ()
      case Success(job)                                          =>
        dataService.initialize()
        syncingBanks.set(false)
        val base = s"Imported ${job.imported} new transaction(s)"
        syncMessage.set(job.message match {
          case Some(msg) => Some((false, s"Sync failed: $msg"))
          case None      => Some((job.errors.isEmpty, if job.errors.nonEmpty then s"$base · some banks had issues (see Banking)" else base))
        })
      case Failure(ex)                                           =>
        syncingBanks.set(false)
        syncMessage.set(Some((false, s"Lost track of sync: ${ex.getMessage}")))
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
      headlineCard(),
      div(
        cls := "row g-3",
        // Left: why the headline is what it is, and the balances behind it. Right: the plan — everything still expected to move.
        div(cls := "col-lg-5", freeMoneyCard(), accountsCard()),
        div(cls := "col-lg-7", PlanCard()),
      ),
      // Full width, below the two columns: triage rows are the one thing here that needs room for a bank description to stay readable.
      TriageCard(api),
    )
  }

  /** The whole budget as one equation, read left to right: what's in the accounts, what's actually free of it, and what that is per remaining day. */
  private def headlineCard(): HtmlElement =
    div(
      cls := "card mb-3",
      div(
        cls := "card-body py-2",
        div(
          cls := "d-flex align-items-end flex-wrap gap-3",
          figure("BALANCE", MoneyFormatter.formatChild(dataService.bankAccountBalance), "fw-bold"),
          operator("→"),
          figure("FREE", MoneyFormatter.formatChild(dataService.freeMoney), "fw-bold text-success"),
          operator("÷"),
          figure("DAYS LEFT", child.text <-- dataService.daysRemainingInPeriod.map(_.toString), ""),
          operator("="),
          figure("PER DAY", MoneyFormatter.formatChild(dataService.dailyBudget), "fw-bold text-primary"),
        ),
        periodStrip(),
      ),
    )

  private def figure(label: String, value: Modifier[HtmlElement], valueCls: String): HtmlElement =
    div(
      div(cls := "text-muted small lh-1", label),
      div(cls := s"fs-4 font-monospace lh-sm $valueCls", value),
    )

  private def operator(symbol: String): HtmlElement =
    div(cls := "fs-5 text-muted pb-1", symbol)

  /** The period, at the weight it deserves on this page: a hairline of progress and one line of text that links out to the full history. */
  private def periodStrip(): HtmlElement =
    div(
      cls := "mt-2",
      child <-- dataService.currentPeriod.map {
        case Some(period) =>
          div(
            div(
              cls       := "progress",
              styleAttr := "height: 3px",
              div(
                cls  := "progress-bar bg-secondary",
                role := "progressbar",
                styleAttr <-- dataService.daysRemainingInPeriod.map(_ => s"width: ${Formatting.periodProgress(period.startDate)}%"),
              ),
            ),
            div(
              cls       := "d-flex justify-content-between small text-muted mt-1",
              span(s"Period started ${Formatting.formatDate(period.startDate)} · day ${Formatting.daysElapsed(period.startDate) + 1}"),
              a(
                cls := "text-muted text-decoration-none",
                Router.linkTo(Page.Periods),
                child.text <-- dataService.daysRemainingInPeriod.map(d => s"$d days left · periods →"),
              ),
            ),
          )
        case None         =>
          div(
            cls := "d-flex justify-content-between small text-muted",
            span("No active period"),
            a(cls := "text-muted text-decoration-none", Router.linkTo(Page.Periods), "start one →"),
          )
      },
    )

  /** How the headline's FREE figure is arrived at — the same arithmetic spelled out line by line, so a surprising number can be traced to its cause.
    */
  private def freeMoneyCard(): HtmlElement =
    div(
      cls := "card mb-3",
      div(cls := "card-header py-2", span("Free money")),
      div(
        cls   := "card-body py-2 font-monospace small",
        accountingRow("Balance", dataService.bankAccountBalance, positive = true, bold = true),
        // The same two figures the Plan card totals, under the same names: whichever kind of entry they came from doesn't change the arithmetic, and
        // splitting them by provenance here only asked the reader to add up four numbers instead of two.
        accountingRow("+ Still to receive", dataService.stillToReceive, positive = true),
        accountingRow("- Still to pay", dataService.stillToPay, positive = false),
        hr(cls := "my-1"),
        accountingRow("= Free money", dataService.freeMoney, positive = true, bold = true),
      ),
      // Informational only (not part of Free Money): actual net change in savings balances this period (+saved / −withdrawn).
      div(
        cls   := "card-footer py-1 d-flex justify-content-between small text-muted",
        span("Savings this period"),
        span(cls := "font-monospace", MoneyFormatter.formatChild(dataService.savingsPeriodChange)),
      ),
    )

  private def accountingRow(label: String, amount: Signal[Money], positive: Boolean, bold: Boolean = false): HtmlElement = {
    val textCls = if positive then "text-success" else "text-danger"
    val fontCls = if bold then "fw-bold" else ""
    div(
      cls := s"d-flex justify-content-between $fontCls",
      span(label),
      span(cls := textCls, MoneyFormatter.formatChild(amount)),
    )
  }

  private def accountsCard(): HtmlElement = {
    div(
      cls := "card mb-3",
      div(
        cls := "card-header py-2 d-flex justify-content-between align-items-center",
        div(
          cls := "d-flex align-items-baseline gap-2",
          span("Accounts"),
          child.maybe <-- lastSyncedAt.map(_.map(t => small(cls := "text-muted", s"synced ${Formatting.formatDateTime(t)}"))),
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
        // The sync outcome belongs next to the balances it changed, not squeezed into the header where a long message wraps the buttons.
        child.maybe <-- syncMessage.signal.map(_.map { case (ok, msg) =>
          div(
            cls := s"px-2 py-1 small border-bottom d-flex justify-content-between ${if ok then "text-success" else "text-danger"}",
            span(msg),
            button(tpe := "button", cls := "btn-close btn-close-sm", onClick --> { _ => syncMessage.set(None) }),
          )
        }),
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
      // Each bucket on its own line, then their sum: "Spending" alone is the headline's BALANCE, and naming the savings subtotal explicitly is what
      // makes it obvious that the grand total is the two added together rather than savings-plus-something.
      div(
        cls := "card-footer py-2",
        div(
          cls := "d-flex justify-content-between",
          span(cls := "fw-bold", "Spending"),
          span(cls := "font-monospace fw-bold", MoneyFormatter.formatChild(dataService.bankAccountBalance)),
        ),
        div(
          cls := "d-flex justify-content-between small text-muted",
          span("Savings"),
          span(cls := "font-monospace", MoneyFormatter.formatChild(dataService.savingsBalance)),
        ),
        div(
          cls := "d-flex justify-content-between small text-muted border-top",
          span("Total"),
          span(cls := "font-monospace", MoneyFormatter.formatChild(dataService.totalBalance)),
        ),
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
