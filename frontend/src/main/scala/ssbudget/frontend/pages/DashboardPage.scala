package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import ssbudget.frontend.components.{ChartKit, FreeMoneyChart, InlineEdit, Loading, PlanCard, TriageCard}
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
        val _ = dom.window.setTimeout(() => pollSyncJob(id), 2000d) // keep polling every 2s while it runs
        ()
      case Success(job)                                          =>
        val _    = dataService.initialize()
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
      onMountCallback(_ => { val _ = dataService.initialize(); () }),
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
        // One ledger of three groups: spending, savings and investments are different kinds of money, so their figures never add up in a row.
        // The heading row of a group carries its totals, so there is no "Total" row to find. Every amount is in the primary currency, so a
        // column can be read down; the own currency of a foreign account is the small line under its balance.
        table(
          cls := "table table-sm table-hover mb-0 accounts-ledger",
          thead(
            tr(
              th(),
              th(cls := "text-end", "Δ period"),
              th(cls := "text-end", child.text <-- dataService.primaryCurrency.map(c => s"Balance, ${c.code}")),
            ),
          ),
          tbody(children <-- ledgerRows),
        ),
        ledgerSummary(),
      ),
    )
  }

  /** Small spending accounts are shown one by one (true), or folded into one line (false). */
  private val showSmallAccounts = Var(false)

  /** A spending account under this balance, in the primary currency, says nothing at a glance. Two or more of them fold into one line. */
  private val smallBalanceCents = 5000L

  private def signedSpan(cents: Long): HtmlElement = span(cls := MoneyFormatter.amountCls(cents), MoneyFormatter.formatSigned(cents))

  /** The rows of the ledger: per group its heading row with the totals, then its accounts. */
  private def ledgerRows: Signal[List[HtmlElement]] =
    dataService.accounts
      .combineWith(dataService.savingsBaselines)
      .combineWith(dataService.investmentMoved)
      .combineWith(dataService.exchangeRates)
      .combineWith(dataService.primaryCurrency)
      .combineWith(isEditingBalances.signal)
      .combineWith(showSmallAccounts.signal)
      .map { case (accounts, baselines, moved, rates, primary, isEditing, showSmall) =>
        // While the balances are edited, the rows stay as they are. A refresh of the data (it arrives a moment after each page load, and after a
        // sync) would make new input fields, and what was typed would be lost. Only a change of the list of accounts makes new rows.
        val editKey = Option.when(isEditing)(accounts.map(_.id))
        editRows.filter(_ => editKey.nonEmpty && editRowsKey == editKey).getOrElse {
          val rows = ledgerRowsOf(accounts, baselines, moved, rates, primary, isEditing, showSmall)
          editRowsKey = editKey
          editRows = Option.when(isEditing)(rows)
          rows
        }
      }

  // The rows of the open edit, and the accounts they were made for (see `ledgerRows`).
  private var editRows: Option[List[HtmlElement]]  = None
  private var editRowsKey: Option[List[AccountId]] = None

  private def ledgerRowsOf(
      accounts: List[Account],
      baselines: Map[AccountId, Money],
      moved: Map[AccountId, Long],
      rates: Map[Currency, Double],
      primary: Currency,
      isEditing: Boolean,
      showSmall: Boolean,
  ): List[HtmlElement] = {
    def inPrimary(cents: Long, currency: Currency): Long =
      MoneyFormatter.convertToPrimary(Money(cents, currency), primary, rates).amountCents
    def balance(a: Account): Long                        = inPrimary(a.balanceCents, a.currency)
    // The change of the period, in the primary currency. No recorded balance at the period's start: no figure, which is not zero.
    def change(a: Account): Option[Long]                 = baselines.get(a.id).map(b => inPrimary(a.balanceCents - b.amountCents, a.currency))
    def of(role: AccountRole): List[Account]             = accounts.filter(_.role == role)

    def groupRow(key: String, label: String, group: List[Account], groupChange: Option[Long]): HtmlElement =
      tr(
        cls                     := "ledger-group",
        dataAttr("group-total") := key,
        td(label, span(cls := "ledger-count", s"${group.size} ${if group.size == 1 then "account" else "accounts"}")),
        td(cls := "ledger-num", groupChange.filter(_ != 0).map(signedSpan)),
        td(cls := "ledger-num", MoneyFormatter.formatBare(group.map(balance).sum)),
      )

    def nameCell(a: Account): HtmlElement = td(a.name)

    // A manual balance is typed in the account's own currency. A bank or card balance comes from a sync and stays as it is.
    def balanceCell(a: Account): HtmlElement =
      if isEditing && a.isManual then td(
        div(
          cls := "input-group input-group-sm",
          input(
            cls          := "form-control form-control-sm text-end",
            tpe          := "number",
            stepAttr     := "0.01",
            defaultValue := InlineEdit.centsText(a.balanceCents),
            // The one parsing rule of the app (exact decimals, comma or dot). Text that is not an amount leaves the account as it was.
            onInput.mapToValue --> { v =>
              editedBalances.update(m => InlineEdit.parseCentsOpt(v).fold(m - a.id)(cents => m.updated(a.id, cents)))
            },
          ),
          span(cls       := "input-group-text py-0", a.currency.code),
        ),
      )
      else
        td(
          cls := s"ledger-num${if isEditing then " text-muted" else ""}",
          MoneyFormatter.formatBare(balance(a)),
          // The own currency of a foreign account: under the amount it explains.
          Option.when(a.currency != primary)(span(cls := "ledger-sub", MoneyFormatter.formatSimple(a.balance))),
        )

    def accountRow(a: Account, changeCell: HtmlElement): HtmlElement =
      tr(cls := (if isEditing && a.isManual then "table-info" else ""), nameCell(a), changeCell, balanceCell(a))

    def changeCell(a: Account): HtmlElement = td(cls := "ledger-num", change(a).filter(_ != 0).map(signedSpan))

    // Spending: no change figure. The balance change of a spending account is only the paycheck less the spend. Small balances fold into one line.
    val spending                                                                = of(AccountRole.Spending)
    val small                                                                   = spending.filter(a => math.abs(balance(a)) < smallBalanceCents)
    val folded                                                                  = if small.size >= 2 && !isEditing && !showSmall then small else Nil
    val limitText                                                               = MoneyFormatter.formatBare(smallBalanceCents)
    def foldToggle(text: String, show: Boolean, sum: Option[Long]): HtmlElement =
      tr(
        td(button(tpe := "button", cls := "btn btn-link btn-sm p-0 text-muted", text, onClick --> { _ => showSmallAccounts.set(show) })),
        td(),
        td(cls := "ledger-num text-muted", sum.map(MoneyFormatter.formatBare)),
      )
    val spendingRows                                                            = groupRow("spending", "Spending", spending, None) ::
      spending.filterNot(folded.contains).map(a => accountRow(a, td())) :::
      (if folded.nonEmpty then List(foldToggle(s"${folded.size} more with less than $limitText", show = true, Some(folded.map(balance).sum)))
       else if small.size >= 2 && showSmall && !isEditing then List(
         foldToggle(s"fold the ${small.size} with less than $limitText", show = false, None),
       )
       else Nil)

    val savings     = of(AccountRole.Savings)
    val savingsRows = groupRow("savings", "Savings", savings, Some(savings.flatMap(change).sum)) :: savings.map(a => accountRow(a, changeCell(a)))

    // Investments: the change of an account has two parts, and only "moved" is saving. The group shows only when there is such an account.
    val investments    = of(AccountRole.Investment)
    val investmentRows =
      if investments.isEmpty then Nil
      else {
        groupRow("investments", "Investments", investments, Some(investments.flatMap(change).sum)) ::
          investments.map { a =>
            val cell = change(a) match {
              case None    => td()
              case Some(c) =>
                val movedIn = moved.getOrElse(a.id, 0L)
                td(
                  cls := "ledger-num",
                  Option.when(c != 0)(signedSpan(c)),
                  span(
                    cls   := "ledger-sub",
                    title := "Deposits less withdrawals and payments, from the transactions of the account and of its transfer categories",
                    s"moved ${MoneyFormatter.formatSigned(movedIn)}",
                  ),
                  span(
                    cls   := "ledger-sub",
                    title := "The change of value that is not a deposit, a withdrawal or a payment",
                    s"market ${MoneyFormatter.formatSigned(c - movedIn)}",
                  ),
                )
            }
            accountRow(a, cell)
          }
      }

    spendingRows ::: savingsRows ::: investmentRows
  }

  /** The line under the ledger: what the period did to the money that is not for spending, and the sum of all three groups. */
  private def ledgerSummary(): HtmlElement = {
    def cell(label: String, hint: String, value: Signal[Money], signed: Boolean, note: Signal[String]): HtmlElement =
      div(
        title := hint,
        div(cls := "ledger-label", label),
        div(
          cls   := "ledger-value",
          child <-- value.map(m => if signed then signedSpan(m.amountCents) else span(MoneyFormatter.formatBare(m.amountCents))),
        ),
        div(cls := "ledger-note", child.text <-- note),
      )
    val hasInvestments                                                                                              = dataService.investmentAccounts.map(_.nonEmpty).distinct
    val saved                                                                                                       = cell(
      "Saved this period",
      "The change of the savings accounts plus what was moved into the investment accounts. The market result is not in it.",
      dataService.savedThisPeriod,
      signed = true,
      dataService.savingsPeriodChange.combineWith(dataService.investmentMovedTotal).combineWith(hasInvestments).map { case (change, moved, has) =>
        if has then s"savings ${MoneyFormatter.formatSigned(change.amountCents)} · moved ${MoneyFormatter.formatSigned(moved.amountCents)}"
        else "the change of the savings accounts"
      },
    )
    val market                                                                                                      = cell(
      "Market result",
      "The change of value of the investment accounts that is not a deposit, a withdrawal or a payment",
      dataService.investmentMarketResult,
      signed = true,
      Val("not counted as saving"),
    )
    val netWorth                                                                                                    = cell(
      "Net worth",
      "The balance of all accounts",
      dataService.totalBalance,
      signed = false,
      hasInvestments.map(has => if has then "all three groups" else "spending and savings"),
    )
    div(cls := "ledger-summary", children <-- hasInvestments.map(has => if has then List(saved, market, netWorth) else List(saved, netWorth)))
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
        // Only a balance that was changed is written: each write adds a record to the balance history.
        .flatMap(acc => edited.get(acc.id).filter(_ != acc.balanceCents).map(amount => dataService.updateAccountBalance(acc.id, amount)))

    Future.sequence(futures).map { _ =>
      isEditingBalances.set(false)
      editedBalances.set(Map.empty)
    }
  }

  private def buildSummaryText(): String = {
    import com.raquo.airstream.ownership.OneTimeOwner
    given owner: OneTimeOwner = new OneTimeOwner(() => ())

    val balance       = dataService.bankAccountBalance.observe.now()
    val stillToPay    = dataService.stillToPay.observe.now()
    val freeMoney     = dataService.freeMoney.observe.now()
    val dailyBudget   = dataService.dailyBudget.observe.now()
    val daysRemaining = dataService.daysRemainingInPeriod.observe.now()

    val dateStr = DateTimeFormatter.ofPattern("MMM d").format(Instant.now().atZone(ZoneOffset.UTC))
    s"""Budget Update ($dateStr)
       |Balance: ${MoneyFormatter.formatSimple(balance)}
       |Still to pay: ${MoneyFormatter.formatSimple(stillToPay)}
       |Free: ${MoneyFormatter.formatSimple(freeMoney)}
       |Daily: ${MoneyFormatter.formatSimple(dailyBudget)} ($daysRemaining days left)""".stripMargin
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
    val _          = dom.window.open(url, "_blank")
  }
}
