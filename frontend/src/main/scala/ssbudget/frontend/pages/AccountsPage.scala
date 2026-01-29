package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import ssbudget.frontend.components.Loading
import ssbudget.frontend.services.DataService
import ssbudget.frontend.util.{Formatting, MoneyFormatter}
import ssbudget.shared.model.*

import scala.concurrent.ExecutionContext.Implicits.global

object AccountsPage {

  private val dataService = DataService.instance

  // Bank accounts state
  private val editingAccountId = Var[Option[AccountId]](None)
  private val addingAccount    = Var(false)

  // Savings accounts state
  private val editingSavingsId = Var[Option[SavingsAccountId]](None)
  private val addingSavings    = Var(false)

  def apply(): HtmlElement = {
    div(
      cls := "container-fluid mt-3",
      h4("Accounts"),
      // Bank Accounts Card
      bankAccountsCard(),
      // Savings Accounts Card
      div(cls := "mt-3", savingsAccountsCard()),
    )
  }

  // ============ Bank Accounts ============

  private def bankAccountsCard(): HtmlElement = {
    div(
      cls := "card",
      div(
        cls := "card-header py-2 d-flex justify-content-between align-items-center",
        span("Bank Accounts"),
        button(cls := "btn btn-sm btn-outline-primary", "+ Add", onClick --> { _ => addingAccount.set(true) }),
      ),
      div(
        cls := "card-body p-0",
        table(
          cls := "table table-sm table-hover mb-0",
          thead(
            tr(th("Account"), th("Currency"), th(cls := "text-end", "Balance"), th("Last Updated"), th("Actions")),
          ),
          tbody(
            children <-- dataService.accounts
              .combineWith(dataService.balanceSnapshots)
              .combineWith(editingAccountId.signal)
              .map { case (accounts, snapshots, editingId) =>
                accounts.map { account =>
                  val snapshot = snapshots.find(_.accountId == account.id)
                  accountRow(account, snapshot, editingId)
                }
              },
            child <-- addingAccount.signal
              .combineWith(dataService.enabledCurrencies)
              .combineWith(dataService.primaryCurrency)
              .map {
                case (true, currencies, primary) => addAccountRow(currencies, primary)
                case (false, _, _)               => emptyNode
              },
          ),
        ),
      ),
      div(
        cls := "card-footer py-2",
        div(
          cls := "d-flex justify-content-between",
          div(
            span(cls := "fw-bold", "Total: "),
            span(cls := "font-monospace fw-bold text-primary", MoneyFormatter.formatChild(dataService.totalBalance)),
          ),
          child <-- dataService.exchangeRates.combineWith(dataService.primaryCurrency).map { case (rates, primary) =>
            if rates.isEmpty then emptyNode
            else {
              val rateStrings = rates.map { case (currency, rate) => s"${currency.code}→${primary.code}: $rate" }.mkString(", ")
              div(cls := "text-muted small", s"Rates: $rateStrings")
            }
          },
        ),
      ),
    )
  }

  private def accountRow(
      account: Account,
      snapshotOpt: Option[BalanceSnapshot],
      editingId: Option[AccountId],
  ): HtmlElement = {
    if editingId.contains(account.id) then editAccountRow(account)
    else {
      val balanceEl = snapshotOpt.fold[HtmlElement](span("-"))(s => MoneyFormatter.format(s.amount, s.currency))
      val dateStr   = snapshotOpt.fold("-")(s => Formatting.formatDate(s.recordedAt))

      tr(
        td(account.name),
        td(span(cls := "badge text-bg-secondary", account.currency.code)),
        td(cls := "text-end font-monospace", balanceEl),
        td(cls := "text-muted small", dateStr),
        td(button(cls := "btn btn-outline-secondary btn-sm", "Edit", onClick --> { _ => editingAccountId.set(Some(account.id)) })),
      )
    }
  }

  private def editAccountRow(account: Account): HtmlElement = {
    var nameRef: org.scalajs.dom.html.Input = null
    val currencyValue                       = Var(account.currency)

    tr(
      cls := "table-warning",
      td(
        input(
          cls          := "form-control form-control-sm",
          tpe          := "text",
          defaultValue := account.name,
          onMountCallback(ctx => nameRef = ctx.thisNode.ref),
          onMountFocus,
        ),
      ),
      td(
        child <-- dataService.enabledCurrencies.map { currencies =>
          select(
            cls := "form-select form-select-sm",
            currencies.map { curr =>
              option(value := curr.code, selected := (curr == account.currency), curr.code)
            },
            onChange.mapToValue --> { v => currencyValue.set(Currency(v)) },
          )
        },
      ),
      td(colSpan := 3, cls := "text-muted small", "Balance is edited from Dashboard"),
      td(
        div(
          cls := "btn-group btn-group-sm",
          button(
            tpe      := "button",
            cls      := "btn btn-primary btn-sm",
            "Save",
            onClick --> { _ =>
              editingAccountId.set(None)
            },
          ),
          button(tpe := "button", cls := "btn btn-secondary btn-sm", "Cancel", onClick --> { _ => editingAccountId.set(None) }),
          button(
            tpe      := "button",
            cls      := "btn btn-danger btn-sm",
            "Del",
            onClick --> { _ =>
              editingAccountId.set(None)
            },
          ),
        ),
      ),
    )
  }

  private def addAccountRow(currencies: List[Currency], primaryCurrency: Currency): HtmlElement = {
    val currencyValue                       = Var(primaryCurrency)
    var nameRef: org.scalajs.dom.html.Input = null

    tr(
      cls := "table-primary",
      td(
        input(
          cls         := "form-control form-control-sm",
          tpe         := "text",
          placeholder := "Account name",
          onMountCallback(ctx => nameRef = ctx.thisNode.ref),
          onMountFocus,
        ),
      ),
      td(
        select(
          cls := "form-select form-select-sm",
          currencies.map(curr => option(value := curr.code, selected := (curr == primaryCurrency), curr.code)),
          onChange.mapToValue --> { v => currencyValue.set(Currency(v)) },
        ),
      ),
      td(colSpan := 3, cls := "text-muted small", "Initial balance: 0"),
      td(
        div(
          cls := "btn-group btn-group-sm",
          Loading.actionButton(
            "Add",
            () => {
              val name = Option(nameRef).map(_.value.trim).getOrElse("")
              if name.nonEmpty then {
                dataService.addAccount(name, currencyValue.now()).map(_ => addingAccount.set(false))
              } else {
                scala.concurrent.Future.successful(())
              }
            },
            "btn btn-success btn-sm",
          ),
          button(tpe := "button", cls := "btn btn-secondary btn-sm", "Cancel", onClick --> { _ => addingAccount.set(false) }),
        ),
      ),
    )
  }

  // ============ Savings Accounts ============

  private def savingsAccountsCard(): HtmlElement = {
    div(
      cls := "card",
      div(
        cls := "card-header py-2 d-flex justify-content-between align-items-center",
        span("Savings Accounts"),
        button(cls := "btn btn-sm btn-outline-success", "+ Add", onClick --> { _ => addingSavings.set(true) }),
      ),
      div(
        cls := "card-body p-0",
        table(
          cls := "table table-sm table-hover mb-0",
          thead(
            tr(th("Account"), th("Currency"), th(cls := "text-end", "Balance"), th(cls := "text-end", "Target/mo"), th("Actions")),
          ),
          tbody(
            children <-- dataService.savingsAccounts
              .combineWith(editingSavingsId.signal)
              .map { case (accounts, editingId) =>
                accounts.map(account => savingsRow(account, editingId))
              },
            child <-- addingSavings.signal
              .combineWith(dataService.enabledCurrencies)
              .combineWith(dataService.primaryCurrency)
              .map {
                case (true, currencies, primary) => addSavingsRow(currencies, primary)
                case (false, _, _)               => emptyNode
              },
          ),
        ),
      ),
      div(
        cls := "card-footer py-2 text-muted small",
        "Savings transactions are managed from the Budget page",
      ),
    )
  }

  private def savingsRow(account: SavingsAccount, editingId: Option[SavingsAccountId]): HtmlElement = {
    if editingId.contains(account.id) then editSavingsRow(account)
    else {
      val balanceEl = MoneyFormatter.format(account.currentBalance, account.currency)
      val targetEl  = account.plannedMonthly.fold[HtmlElement](span("-"))(t => MoneyFormatter.format(t, account.currency))

      tr(
        td(account.name),
        td(span(cls := "badge text-bg-success", account.currency.code)),
        td(cls := "text-end font-monospace", balanceEl),
        td(cls := "text-end font-monospace text-muted", targetEl),
        td(button(cls := "btn btn-outline-secondary btn-sm", "Edit", onClick --> { _ => editingSavingsId.set(Some(account.id)) })),
      )
    }
  }

  private def editSavingsRow(account: SavingsAccount): HtmlElement = {
    var nameRef: org.scalajs.dom.html.Input   = null
    var targetRef: org.scalajs.dom.html.Input = null
    val currencyValue                         = Var(account.currency)

    tr(
      cls := "table-warning",
      td(
        input(
          cls          := "form-control form-control-sm",
          tpe          := "text",
          defaultValue := account.name,
          onMountCallback(ctx => nameRef = ctx.thisNode.ref),
          onMountFocus,
        ),
      ),
      td(
        child <-- dataService.enabledCurrencies.map { currencies =>
          select(
            cls := "form-select form-select-sm",
            currencies.map { curr =>
              option(value := curr.code, selected := (curr == account.currency), curr.code)
            },
            onChange.mapToValue --> { v => currencyValue.set(Currency(v)) },
          )
        },
      ),
      td(cls := "text-muted small", "Balance: Dashboard"),
      td(
        input(
          cls          := "form-control form-control-sm text-end",
          tpe          := "number",
          stepAttr     := "0.01",
          placeholder  := "No target",
          defaultValue := account.plannedMonthly.fold("")(t => (t / 100.0).toString),
          onMountCallback(ctx => targetRef = ctx.thisNode.ref),
        ),
      ),
      td(
        div(
          cls := "btn-group btn-group-sm",
          Loading.actionButton(
            "Save",
            () => {
              val name      = Option(nameRef).map(_.value.trim).getOrElse("")
              val targetTxt = Option(targetRef).map(_.value.trim).getOrElse("")
              if name.nonEmpty then {
                val targetCents = if targetTxt.isEmpty then None else Some((targetTxt.toDoubleOption.getOrElse(0.0) * 100).toLong)
                dataService.updateSavingsAccount(account.id, name, currencyValue.now(), targetCents).map(_ => editingSavingsId.set(None))
              } else {
                scala.concurrent.Future.successful(())
              }
            },
            "btn btn-primary btn-sm",
          ),
          button(tpe := "button", cls := "btn btn-secondary btn-sm", "Cancel", onClick --> { _ => editingSavingsId.set(None) }),
          Loading.actionButton(
            "Del",
            () => dataService.deleteSavingsAccount(account.id).map(_ => editingSavingsId.set(None)),
            "btn btn-danger btn-sm",
          ),
        ),
      ),
    )
  }

  private def addSavingsRow(currencies: List[Currency], primaryCurrency: Currency): HtmlElement = {
    var nameRef: org.scalajs.dom.html.Input   = null
    var targetRef: org.scalajs.dom.html.Input = null
    val currencyValue                         = Var(primaryCurrency)

    tr(
      cls := "table-success",
      td(
        input(
          cls         := "form-control form-control-sm",
          tpe         := "text",
          placeholder := "Account name",
          onMountCallback(ctx => nameRef = ctx.thisNode.ref),
          onMountFocus,
        ),
      ),
      td(
        select(
          cls := "form-select form-select-sm",
          currencies.map(curr => option(value := curr.code, selected := (curr == primaryCurrency), curr.code)),
          onChange.mapToValue --> { v => currencyValue.set(Currency(v)) },
        ),
      ),
      td(cls := "text-muted small", "Balance: 0"),
      td(
        input(
          cls         := "form-control form-control-sm text-end",
          tpe         := "number",
          stepAttr    := "0.01",
          placeholder := "Target/mo",
          onMountCallback(ctx => targetRef = ctx.thisNode.ref),
        ),
      ),
      td(
        div(
          cls := "btn-group btn-group-sm",
          Loading.actionButton(
            "Add",
            () => {
              val name      = Option(nameRef).map(_.value.trim).getOrElse("")
              val targetTxt = Option(targetRef).map(_.value.trim).getOrElse("")
              if name.nonEmpty then {
                val targetCents = if targetTxt.isEmpty then None else Some((targetTxt.toDoubleOption.getOrElse(0.0) * 100).toLong)
                dataService.addSavingsAccount(name, currencyValue.now(), targetCents).map(_ => addingSavings.set(false))
              } else {
                scala.concurrent.Future.successful(())
              }
            },
            "btn btn-success btn-sm",
          ),
          button(tpe := "button", cls := "btn btn-secondary btn-sm", "Cancel", onClick --> { _ => addingSavings.set(false) }),
        ),
      ),
    )
  }
}
