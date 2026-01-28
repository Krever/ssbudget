package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import ssbudget.frontend.components.Loading
import ssbudget.frontend.services.DataService
import ssbudget.frontend.util.Formatting
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
            tr(th("Account"), th("Currency"), th(cls := "text-end", "Balance"), th(cls := "text-end", "In PLN"), th("Last Updated"), th("Actions")),
          ),
          tbody(
            children <-- dataService.accounts
              .combineWith(dataService.balanceSnapshots)
              .combineWith(dataService.exchangeRate)
              .combineWith(editingAccountId.signal)
              .map { case (accounts, snapshots, rate, editingId) =>
                accounts.map { account =>
                  val snapshot = snapshots.find(_.accountId == account.id)
                  accountRow(account, snapshot, rate.rateAsDouble, editingId)
                }
              },
            child <-- addingAccount.signal.map {
              case true  => addAccountRow()
              case false => emptyNode
            },
          ),
        ),
      ),
      div(
        cls := "card-footer py-2",
        div(
          cls := "d-flex justify-content-between",
          div(
            span(cls := "fw-bold", "Total Balance (PLN): "),
            span(
              cls    := "font-monospace fw-bold text-primary",
              child.text <-- dataService.totalBalance.map(_.formatted),
            ),
          ),
          div(cls := "text-muted", child.text <-- dataService.exchangeRate.map(r => s"EUR/PLN: ${r.rateAsDouble}")),
        ),
      ),
    )
  }

  private def accountRow(account: Account, snapshotOpt: Option[BalanceSnapshot], eurToPlnRate: Double, editingId: Option[AccountId]): HtmlElement = {
    if editingId.contains(account.id) then editAccountRow(account)
    else {
      val balanceStr = snapshotOpt.fold("-")(s => Money(s.amount, s.currency).formatted)
      val plnStr     = snapshotOpt.fold("-") { s =>
        if s.currency == Currency.PLN then "-"
        else Money.pln((s.amount * eurToPlnRate).toLong).formatted
      }
      val dateStr    = snapshotOpt.fold("-")(s => Formatting.formatDate(s.recordedAt))

      tr(
        td(account.name),
        td(span(cls := "badge text-bg-secondary", account.currency.toString)),
        td(cls := "text-end font-monospace", balanceStr),
        td(cls := "text-end font-monospace text-muted", plnStr),
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
        select(
          cls := "form-select form-select-sm",
          Currency.values.toSeq.map { curr =>
            option(value := curr.toString, selected := (curr == account.currency), curr.toString)
          },
          onChange.mapToValue --> { v => currencyValue.set(Currency.valueOf(v)) },
        ),
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

  private def addAccountRow(): HtmlElement = {
    val currencyValue                       = Var(Currency.PLN)
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
          Currency.values.toSeq.map(curr => option(value := curr.toString, curr.toString)),
          onChange.mapToValue --> { v => currencyValue.set(Currency.valueOf(v)) },
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
            child <-- addingSavings.signal.map {
              case true  => addSavingsRow()
              case false => emptyNode
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
      val balanceStr = Money(account.currentBalance, account.currency).formatted
      val targetStr  = account.plannedMonthly.fold("-")(t => Money(t, account.currency).formatted)

      tr(
        td(account.name),
        td(span(cls := "badge text-bg-success", account.currency.toString)),
        td(cls := "text-end font-monospace", balanceStr),
        td(cls := "text-end font-monospace text-muted", targetStr),
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
        select(
          cls := "form-select form-select-sm",
          Currency.values.toSeq.map { curr =>
            option(value := curr.toString, selected := (curr == account.currency), curr.toString)
          },
          onChange.mapToValue --> { v => currencyValue.set(Currency.valueOf(v)) },
        ),
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

  private def addSavingsRow(): HtmlElement = {
    var nameRef: org.scalajs.dom.html.Input   = null
    var targetRef: org.scalajs.dom.html.Input = null
    val currencyValue                         = Var(Currency.PLN)

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
          Currency.values.toSeq.map(curr => option(value := curr.toString, curr.toString)),
          onChange.mapToValue --> { v => currencyValue.set(Currency.valueOf(v)) },
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
