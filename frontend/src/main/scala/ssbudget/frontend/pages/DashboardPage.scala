package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import ssbudget.frontend.services.DataService
import ssbudget.frontend.util.Formatting
import ssbudget.shared.model.{Account, BalanceSnapshot, Currency}

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}

object DashboardPage {

  private val dataService = DataService.instance

  private val isEditingBalances = Var(false)
  private val editedBalances    = Var(Map.empty[String, Long])
  private val copyButtonText    = Var("Copy Summary")

  def apply(): HtmlElement = {
    div(
      cls := "container-fluid mt-3",
      div(
        cls := "d-flex justify-content-between align-items-center mb-2",
        h4(cls := "mb-0", "Dashboard"),
        button(
          cls  := "btn btn-sm btn-outline-secondary",
          child.text <-- copyButtonText.signal,
          onClick --> { _ =>
            copySummaryToClipboard()
          },
        ),
      ),
      div(
        cls := "row g-3 mb-4",
        summaryCard("Total Balance", dataService.totalBalancePLN, "text-primary"),
        summaryCard("Available Now", dataService.availableNowCents, "text-info"),
        summaryCard("Free Money", dataService.freeMoneyCents, "text-success"),
        dailyBudgetCard(),
      ),
      div(
        cls := "row g-3",
        div(
          cls := "col-md-6",
          accountsQuickView(),
        ),
        div(
          cls := "col-md-6",
          periodCard(),
        ),
      ),
    )
  }

  private def summaryCard(title: String, amountSignal: Signal[Long], colorClass: String): HtmlElement = {
    div(
      cls := "col-md-3 col-sm-6",
      div(
        cls := "card h-100",
        div(
          cls := "card-body py-2",
          div(cls := "text-muted small", title),
          div(
            cls   := s"fs-4 font-monospace $colorClass",
            child.text <-- amountSignal.map(Formatting.formatMoney(_, Currency.PLN)),
          ),
        ),
      ),
    )
  }

  private def dailyBudgetCard(): HtmlElement = {
    div(
      cls := "col-md-3 col-sm-6",
      div(
        cls := "card h-100",
        div(
          cls := "card-body py-2",
          div(cls := "text-muted small", "Daily Budget"),
          div(
            cls   := "fs-4 font-monospace text-info",
            child.text <-- dataService.dailyBudgetCents.map(Formatting.formatMoney(_, Currency.PLN)),
          ),
          small(
            cls   := "text-muted",
            child.text <-- dataService.daysRemainingInPeriod.map(d => s"$d days left"),
          ),
        ),
      ),
    )
  }

  private def periodCard(): HtmlElement = {
    div(
      cls := "card",
      div(
        cls := "card-header py-2",
        "Current Period",
      ),
      div(
        cls := "card-body py-2",
        child <-- dataService.currentPeriod.map {
          case Some(period) =>
            div(
              div(
                cls       := "d-flex justify-content-between mb-2",
                span(s"Started: ${Formatting.formatDate(period.startDate)}"),
                span(
                  cls := "text-muted",
                  child.text <-- dataService.daysRemainingInPeriod.map(d => s"$d days remaining"),
                ),
              ),
              div(
                cls       := "progress",
                styleAttr := "height: 8px",
                div(
                  cls  := "progress-bar",
                  role := "progressbar",
                  styleAttr <-- dataService.daysRemainingInPeriod.map { _ =>
                    val progress = Formatting.periodProgress(period.startDate)
                    s"width: $progress%"
                  },
                ),
              ),
            )
          case None         =>
            div(cls := "text-muted", "No active period")
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
        child <-- dataService.accounts
          .combineWith(dataService.balanceSnapshots)
          .combineWith(isEditingBalances.signal)
          .combineWith(editedBalances.signal)
          .map { case (accounts, snapshots, isEditing, edited) =>
            if isEditing then div(
              cls   := "btn-group btn-group-sm",
              button(
                cls := "btn btn-success btn-sm py-0",
                "Save All",
                onClick --> { _ =>
                  accounts.foreach { acc =>
                    edited.get(acc.id.value).foreach { amount =>
                      dataService.updateAccountBalance(acc.id, amount)
                    }
                  }
                  isEditingBalances.set(false)
                  editedBalances.set(Map.empty)
                },
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
                onClick --> { _ =>
                  val initial = accounts.map { acc =>
                    val current = snapshots.find(_.accountId == acc.id).map(_.amount).getOrElse(0L)
                    acc.id.value -> current
                  }.toMap
                  editedBalances.set(initial)
                  isEditingBalances.set(true)
                },
              )
          },
      ),
      div(
        cls := "card-body p-0",
        table(
          cls := "table table-sm table-hover mb-0",
          thead(
            tr(
              th("Account"),
              th(cls := "text-end", "Balance"),
            ),
          ),
          tbody(
            children <-- dataService.accounts
              .combineWith(dataService.balanceSnapshots)
              .combineWith(isEditingBalances.signal)
              .map { case (accounts, snapshots, isEditing) =>
                accounts.map { account =>
                  val balance = snapshots.find(_.accountId == account.id)
                  accountQuickRow(account, balance, isEditing)
                }
              },
          ),
        ),
      ),
      div(
        cls := "card-footer py-2 d-flex justify-content-between",
        span(cls := "fw-bold", "Total (PLN)"),
        span(
          cls    := "font-monospace fw-bold",
          child.text <-- dataService.totalBalancePLN.map(Formatting.formatMoney(_, Currency.PLN)),
        ),
      ),
    )
  }

  private def accountQuickRow(
      account: Account,
      balanceOpt: Option[BalanceSnapshot],
      isEditing: Boolean,
  ): HtmlElement = {
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
            onInput.mapToValue --> { v =>
              v.toDoubleOption.foreach { d =>
                editedBalances.update(_.updated(account.id.value, (d * 100).toLong))
              }
            },
          ),
          span(cls       := "input-group-text py-0", account.currency.toString),
        ),
      ),
    )
    else
      tr(
        td(account.name),
        td(
          cls := "text-end font-monospace",
          balanceOpt.fold("-")(b => Formatting.formatMoney(b.amount, b.currency)),
        ),
      )
  }

  private def copySummaryToClipboard(): Unit = {
    import com.raquo.airstream.ownership.OneTimeOwner
    given owner: OneTimeOwner = new OneTimeOwner(() => ()) // needed for observe.now()

    val balance       = dataService.totalBalancePLN.observe.now()
    val availableNow  = dataService.availableNowCents.observe.now()
    val freeMoney     = dataService.freeMoneyCents.observe.now()
    val dailyBudget   = dataService.dailyBudgetCents.observe.now()
    val daysRemaining = dataService.daysRemainingInPeriod.observe.now()

    val dateStr = DateTimeFormatter
      .ofPattern("MMM d")
      .format(Instant.now().atZone(ZoneOffset.UTC))

    val summary =
      s"""Budget Update ($dateStr)
         |Balance: ${Formatting.formatMoney(balance, Currency.PLN)}
         |Available: ${Formatting.formatMoney(availableNow, Currency.PLN)}
         |Free: ${Formatting.formatMoney(freeMoney, Currency.PLN)}
         |Daily: ${Formatting.formatMoney(dailyBudget, Currency.PLN)} ($daysRemaining days left)""".stripMargin

    dom.window.navigator.clipboard
      .writeText(summary)
      .toFuture
      .foreach { _ =>
        copyButtonText.set("Copied!")
        dom.window.setTimeout(() => copyButtonText.set("Copy Summary"), 2000)
      }(scala.concurrent.ExecutionContext.global)
  }
}
