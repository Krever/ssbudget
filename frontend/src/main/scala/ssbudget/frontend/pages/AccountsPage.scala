package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import ssbudget.frontend.services.DataService
import ssbudget.frontend.util.Formatting
import ssbudget.shared.model.{Account, AccountId, BalanceSnapshot, Currency, Money}

object AccountsPage {

  private val dataService = DataService.instance

  private val editingAccountId = Var[Option[AccountId]](None)
  private val addingAccount    = Var(false)

  def apply(): HtmlElement = {
    div(
      cls := "container-fluid mt-3",
      h4("Accounts"),
      div(
        cls := "card",
        div(
          cls := "card-header py-2 d-flex justify-content-between align-items-center",
          span("All Accounts"),
          button(cls := "btn btn-sm btn-outline-primary", "+ Add Account", onClick --> { _ => addingAccount.set(true) }),
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
              // TODO: implement updateAccount when backend is ready
              editingAccountId.set(None)
            },
          ),
          button(tpe := "button", cls := "btn btn-secondary btn-sm", "Cancel", onClick --> { _ => editingAccountId.set(None) }),
          button(
            tpe      := "button",
            cls      := "btn btn-danger btn-sm",
            "Del",
            onClick --> { _ =>
              // TODO: implement deleteAccount when backend is ready
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
          button(
            tpe      := "button",
            cls      := "btn btn-success btn-sm",
            "Add",
            onClick --> { _ =>
              val name = Option(nameRef).map(_.value.trim).getOrElse("")
              if name.nonEmpty then {
                dataService.addAccount(name, currencyValue.now())
                addingAccount.set(false)
              }
            },
          ),
          button(tpe := "button", cls := "btn btn-secondary btn-sm", "Cancel", onClick --> { _ => addingAccount.set(false) }),
        ),
      ),
    )
  }
}
