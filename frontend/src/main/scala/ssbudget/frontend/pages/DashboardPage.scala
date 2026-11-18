package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import ssbudget.frontend.components.{Badges, ChartKit, FreeMoneyChart, InlineEdit, Loading, PlanCard, TriageCard}
import ssbudget.frontend.services.{ApiClient, DataService}
import ssbudget.frontend.util.{Formatting, MoneyFormatter}
import ssbudget.frontend.{Page, Router}
import ssbudget.shared.model.*

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate, ZoneOffset}
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

  /** The current period's points, their projection, and the projected end from each day at the usual pace.
    *
    * Today's point is the live figure, not the stored row. The stored row is as old as the last fetch, and the live figure moves with each edit on
    * this page, so the line always ends on the headline's FREE value. The ledger terms of today are taken from the latest stored row: it changes only
    * when the bank is synced, and then the history is loaded again.
    */
  private val freeMoneyTrend: Signal[Option[(List[FreeMoneyPoint], Option[FreeMoneyProjection], List[(LocalDate, Long)], Currency)]] =
    dataService.freeMoneyHistory
      .combineWith(dataService.currentPeriod)
      .combineWith(dataService.freeMoney)
      .map { case (history, periodOpt, free) =>
        periodOpt.map { period =>
          val today  = DataService.today
          val stored = history.getOrElse(period.id, Nil)
          val now    = FreeMoneyPoint(
            today,
            free.amountCents,
            stored.lastOption.fold(0L)(_.planLineCents),
            stored.lastOption.fold(0L)(_.fromSavingsCents),
            stored.lastOption.fold(0L)(_.unplannedIncomeCents),
            measured = true,
            // The causes are those of today's stored row. An older latest row belongs to another day, and its causes are not today's.
            stored.lastOption.filter(_.day == today).fold(Nil)(_.causes),
          )
          val points = stored.filter(_.day.isBefore(today)) :+ now
          (points, FreeMoneyTrend.project(period, points), FreeMoneyTrend.projectionHistory(period, points), free.currency)
        }
      }
      // A reload that brings the same figures emits again. `distinct`, so that the chart is made again only when its figures are different.
      .distinct

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
                cls       := "progress-bar bg-secondary",
                role      := "progressbar",
                styleAttr := Formatting.progressWidth(period.elapsedFraction(DataService.today)),
              ),
            ),
            div(
              cls       := "d-flex justify-content-between small text-muted mt-1",
              span(s"Period started ${Formatting.formatDate(period.startDate)} · day ${period.dayOfPeriod(DataService.today)}"),
              a(
                cls := "text-muted text-decoration-none",
                Router.linkTo(Page.Periods),
                s"${period.daysRemaining(DataService.today)} days left · periods →",
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
        // The planned saving of the period. One row at a time, by the sign of what it takes from free money: no plan yet, a part of the plan
        // not yet moved, or more than the plan moved. The word "plan" in the row is the control: a click on it opens the field.
        child.maybe <-- dataService.currentPeriod.combineWith(dataService.savingReserve).map { case (periodOpt, reserve) =>
          periodOpt.map { period =>
            if period.plannedSavingCents <= 0 then {
              div(cls := "d-flex justify-content-between text-muted", span("Planned saving: ", plannedSavingControl(period)))
            } else if reserve.amountCents < 0 then {
              div(
                dataAttr("saving-reserve") := reserve.amountCents.toString,
                title                      := "More than this period's planned saving was moved to a savings account. The part above the plan " +
                  "is not saving: it is counted as money for what is still to pay, for example bills that are taken from that account.",
                accountingRow(
                  span("+ In savings above the ", plannedSavingControl(period)),
                  Val(Money(-reserve.amountCents, reserve.currency)),
                  positive = true,
                ),
              )
            } else {
              div(
                dataAttr("saving-reserve") := reserve.amountCents.toString,
                title                      := "The part of this period's planned saving that is not yet moved to a savings account. " +
                  "It is still in the spending balance, and it is not free.",
                accountingRow(span("- Still to save (", plannedSavingControl(period), ")"), Val(reserve), positive = false),
              )
            }
          }
        },
        hr(cls := "my-1"),
        accountingRow("= Free money", dataService.freeMoney, positive = true, bold = true),
        // Below the arithmetic, not in it: the rows above are the plan, and this is where the plan goes if it continues to lose at its usual
        // daily rate. Absent until there are two days to draw.
        child.maybe <-- freeMoneyTrend.map {
          _.flatMap { case (points, projection, forecasts, currency) =>
            FreeMoneyChart(points, projection, forecasts, currency, ChartKit.whiteSurface).map { chart =>
              div(
                projection.map { p =>
                  div(
                    cls                       := "d-flex justify-content-between text-muted",
                    dataAttr("projected-end") := p.endCents.toString,
                    title                     := "Free money now, plus the median daily change of this period for each remaining day. " +
                      "Savings transfers and unplanned income are not part of that change.",
                    span(
                      "→ Projected at period end",
                      // Do the last days go better or worse than the projection expects? Green when the expected end moved up.
                      FreeMoneyTrend.projectionChange(forecasts).map { case (since, change) =>
                        span(
                          cls                          := s"ms-2 small ${MoneyFormatter.amountCls(change)}",
                          dataAttr("projected-change") := change.toString,
                          title                        := "Change of the projected end across the last days, at the usual daily change. " +
                            "Positive: these days were better than the usual day of this period.",
                          s"${MoneyFormatter.formatSigned(change)} since ${Formatting.formatDay(since)}",
                        )
                      },
                    ),
                    span(cls := MoneyFormatter.amountCls(p.endCents), MoneyFormatter.formatSimple(p.endCents, currency)),
                  )
                },
                div(cls := "mt-2", chart),
              )
            }
          }
        },
      ),
    )

  /** `true` while the planned saving is open for typing in the Free money card. */
  private val editingPlannedSaving = Var(false)

  /** The period's planned saving as text that becomes a field on a click, in the primary currency.
    *
    * The field is saved when it loses focus, and Enter leaves the field. Escape closes it with no change. An empty field is zero: no plan. Text that
    * is not an amount is ignored. The same amount can be typed on the Periods page.
    */
  private def plannedSavingControl(period: Period): HtmlElement =
    span(
      child <-- editingPlannedSaving.signal.map {
        case false =>
          span(
            cls                        := "text-decoration-underline",
            styleAttr                  := "cursor: pointer; text-decoration-style: dotted",
            role                       := "button",
            title                      := (if period.plannedSavingCents <= 0 then "Click to plan a saving for this period"
                      else s"Planned saving: ${MoneyFormatter.formatBare(period.plannedSavingCents)}. Click to change it."),
            dataAttr("planned-saving") := period.plannedSavingCents.toString,
            if period.plannedSavingCents <= 0 then "set" else "plan",
            onClick --> (_ => editingPlannedSaving.set(true)),
          )
        case true  =>
          // Escape removes the field, and some browsers send `blur` for a field that is removed. The flag stops that blur from saving.
          var cancelled                              = false
          InlineEdit
            .moneyInput(Option.when(period.plannedSavingCents > 0)(period.plannedSavingCents), placeholderText = "0", autoFocus = true)
            .amend(
              cls                              := "d-inline-block py-0",
              styleAttr                        := "width: 7rem",
              dataAttr("planned-saving-input") := "",
              InlineEdit.commitOnEnter,
              onKeyDown.filter(_.key == "Escape") --> { _ =>
                cancelled = true
                editingPlannedSaving.set(false)
              },
              onBlur --> { ev =>
                if !cancelled then {
                  InlineEdit
                    .parseCentsOrZero(ev.target.asInstanceOf[dom.html.Input].value)
                    .filter(_ != period.plannedSavingCents)
                    .foreach(dataService.setPeriodPlannedSaving(period.id, _))
                  editingPlannedSaving.set(false)
                }
              },
            )
      },
    )

  private def accountingRow(label: Modifier[HtmlElement], amount: Signal[Money], positive: Boolean, bold: Boolean = false): HtmlElement = {
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
        // Two self-contained groups, each closed by its own total the way a spreadsheet sums a block — spending and savings are different kinds of
        // money, so their figures never share a row or add up to a grand total. The spending total is the headline's BALANCE; the savings total
        // carries the group's Δ sum (net saved/withdrawn this period, in the primary currency). The Δ column is savings-only (see
        // AccountPeriodBaseline), so its header lives on the savings group heading rather than in the table head over rows it never applies to.
        table(
          cls := "table table-sm table-hover mb-0",
          thead(tr(th("Account"), th(), th(cls := "text-end", "Balance"))),
          tbody(
            groupHeading("— Spending —"),
            children <-- dataService.spendingAccounts
              .combineWith(isEditingBalances.signal)
              .map { case (accounts, isEditing) =>
                accounts.map(account => accountQuickRow(account, isEditing))
              },
            subtotalRow("spending", None, dataService.bankAccountBalance),
            groupHeading("— Savings —", deltaHeader = "Δ period"),
            children <-- dataService.savingsAccounts
              .combineWith(dataService.savingsPeriodDeltas)
              .combineWith(isEditingBalances.signal)
              .map { case (accounts, deltas, isEditing) =>
                accounts.map(account => accountQuickRow(account, isEditing, deltas.get(account.id)))
              },
            subtotalRow("savings", Some(dataService.savingsPeriodChange), dataService.savingsBalance),
            // Investments are a third kind of money: at market value, so their change is not what was saved. The group shows only when there is
            // such an account. Its change is split into what was moved (that part is saving) and what the market did.
            children <-- dataService.investmentAccounts
              .combineWith(dataService.investmentPeriodDeltas)
              .combineWith(isEditingBalances.signal)
              .map { case (accounts, deltas, isEditing) =>
                if accounts.isEmpty then Nil
                else {
                  groupHeading("— Investments —", deltaHeader = "Δ period") ::
                    accounts.map(account => accountQuickRow(account, isEditing, deltas.get(account.id))) :::
                    List(
                      subtotalRow("investments", Some(dataService.investmentPeriodChange), dataService.investmentBalance),
                      partRow(
                        "moved in / out",
                        "Deposits less withdrawals, from the transactions of the transfer categories",
                        dataService.investmentMovedTotal,
                      ),
                      partRow("market result", "The change of value that is not a deposit or a withdrawal", dataService.investmentMarketResult),
                      groupHeading("— Saved this period —"),
                      partRow(
                        "savings change + moved to investments",
                        "The change of the savings accounts plus what was moved into the investment accounts. The market result is not in it.",
                        dataService.savedThisPeriod,
                        strong = true,
                      ),
                    )
                }
              },
          ),
        ),
      ),
    )
  }

  private def groupHeading(label: String, deltaHeader: String = ""): HtmlElement = {
    // Styling must sit on the cells: table-secondary sets its text colour directly on them, so classes on the row don't take.
    val cell = cls := "py-1 small text-muted"
    tr(cls := "table-secondary", td(cell, label), td(cell, cls := "text-end", deltaHeader), td(cell))
  }

  private val deltaCellCls = "text-end font-monospace small"

  /** A balance change this period: signed, coloured, converted like a balance — but silent when absent or when nothing moved (a column of +0.00 would
    * drown the few real movements).
    */
  private def deltaSpan(delta: Money): HtmlElement =
    if delta.amountCents == 0 then span() else MoneyFormatter.formatDelta(delta)

  /** One named part of a group's change: a label and a signed amount in the Δ column. */
  private def partRow(label: String, hint: String, amount: Signal[Money], strong: Boolean = false): HtmlElement =
    tr(
      cls   := (if strong then "fw-bold" else "text-muted small"),
      title := hint,
      td(cls := (if strong then "" else "ps-4"), label),
      td(cls := deltaCellCls, child <-- amount.map(MoneyFormatter.formatDelta)),
      td(),
    )

  private def subtotalRow(group: String, delta: Option[Signal[Money]], balance: Signal[Money]): HtmlElement =
    tr(
      // table-group-divider doubles the top border, marking where the rows stop and their sum begins.
      cls                     := "fw-bold table-group-divider",
      dataAttr("group-total") := group,
      td("Total"),
      td(cls := deltaCellCls, delta.map(d => child <-- d.map(deltaSpan))),
      td(cls := "text-end font-monospace", MoneyFormatter.formatChild(balance)),
    )

  private def accountQuickRow(account: Account, isEditing: Boolean, delta: Option[Money] = None): HtmlElement = {
    val nameCell    = td(account.name, Badges.source(account.balanceSource))
    val deltaCell   = td(cls := deltaCellCls, delta.map(deltaSpan))
    val balanceView = MoneyFormatter.format(account.balanceCents, account.currency)

    if isEditing && !account.isManual then
    // Bank/card-driven balance: not manually editable, updated via Sync banks.
    tr(nameCell, deltaCell, td(cls := "text-end font-monospace text-muted", balanceView))
    else if isEditing then tr(
      cls := "table-info",
      nameCell,
      deltaCell,
      td(
        div(
          cls := "input-group input-group-sm",
          input(
            cls          := "form-control form-control-sm text-end",
            tpe          := "number",
            stepAttr     := "0.01",
            defaultValue := InlineEdit.centsText(account.balanceCents),
            onInput.mapToValue --> { v => v.toDoubleOption.foreach(d => editedBalances.update(_.updated(account.id, (d * 100).toLong))) },
          ),
          span(cls       := "input-group-text py-0", account.currency.code),
        ),
      ),
    )
    else tr(nameCell, deltaCell, td(cls := "text-end font-monospace", balanceView))
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
