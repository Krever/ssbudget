package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import ssbudget.frontend.components.{Badges, Loading}
import ssbudget.frontend.util.{Formatting, MoneyFormatter}
import ssbudget.frontend.services.DataService
import ssbudget.shared.model.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** Where accounts are made, renamed, given a type and deleted. Balances are edited on the Dashboard.
  *
  * It is the ledger of the Dashboard's Accounts card, with the same groups in the same order (`accounts-ledger` in `app.css`): one table, a heading
  * row for each account type with the total of the group, and one row for each account. An account shows in the group of its type, so the type has no
  * column; it is a field of the edit row. Every group is shown, also an empty one, because this is the page where its first account is made.
  */
object AccountsPage {

  private val dataService = DataService.instance

  /** The account types, in the order of the Dashboard's ledger. */
  private val groups: List[AccountRole] = List(AccountRole.Spending, AccountRole.Holding, AccountRole.Savings, AccountRole.Investment)

  private def groupLabel(role: AccountRole): String = role match {
    case AccountRole.Spending   => "Spending"
    case AccountRole.Holding    => "On hold"
    case AccountRole.Savings    => "Savings"
    case AccountRole.Investment => "Investments"
  }

  private def typeLabel(role: AccountRole): String = role match {
    case AccountRole.Investment => "Investment"
    case other                  => groupLabel(other)
  }

  def apply(): HtmlElement = {
    // What is open: the account in its edit row, and the type whose add row shows. The state is of this visit of the page: a row that was
    // left open does not wait there on the next visit.
    val editing = Var(Option.empty[AccountId])
    val adding  = Var(Option.empty[AccountRole])

    val rows = dataService.accounts
      .combineWith(editing.signal)
      .combineWith(adding.signal)
      .combineWith(dataService.enabledCurrencies)
      .combineWith(dataService.primaryCurrency)
      .combineWith(dataService.exchangeRates)
      .map { case (accounts, editingId, addingTo, currencies, primary, rates) =>
        groups.flatMap { role =>
          val inGroup = accounts.filter(_.role == role).sortBy(_.name.toLowerCase)
          val total   = DataService.sumInPrimary(inGroup.map(a => Money(a.balanceCents, a.currency)), rates, primary)
          groupRow(role, inGroup.size, total, adding) ::
            inGroup.map { account =>
              if editingId.contains(account.id) then editRow(account, currencies, editing)
              else accountRow(account, primary, rates, editing)
            } :::
            Option.when(addingTo.contains(role))(addRow(role, currencies, primary, adding)).toList
        }
      }

    div(
      cls := "container-fluid mt-3",
      h4("Accounts"),
      div(
        cls              := "card",
        dataAttr("card") := "accounts",
        div(
          cls := "card-body p-0",
          table(
            cls := "table table-sm table-hover mb-0 accounts-ledger",
            thead(tr(th("Account"), th("Currency"), th(cls := "text-end", "Balance"), th("Updated"), th())),
            tbody(children <-- rows),
          ),
        ),
        div(
          cls := "card-footer py-2 text-muted small",
          div(
            "Balances are edited on the Dashboard. On hold is money that waits for the next period: not spendable, and not savings. An " +
              "investment is money at market value: its change is not counted as saving, only what you move in or out is. To record those " +
              "moves, set the type of a category to Transfer on the Transactions page.",
          ),
          // The rates of the currencies that the accounts use. The server holds a rate for many more.
          child.maybe <-- dataService.exchangeRates.combineWith(dataService.primaryCurrency).combineWith(dataService.accounts).map {
            case (rates, primary, accounts) =>
              val used = accounts.map(_.currency).distinct.filter(_ != primary).sortBy(_.code)
              val text = used.flatMap(c => rates.get(c).map(rate => s"${c.code}→${primary.code} $rate"))
              Option.when(text.nonEmpty)(div(cls := "mt-1", "Rates: " + text.mkString(", ")))
          },
        ),
      ),
    )
  }

  /** The heading row of a type: its name, how many accounts it has, their total in the primary currency, and the way to add one. */
  private def groupRow(role: AccountRole, count: Int, total: Money, adding: Var[Option[AccountRole]]): HtmlElement =
    tr(
      cls               := "ledger-group",
      dataAttr("group") := AccountRole.asString(role),
      td(groupLabel(role), span(cls := "ledger-count", count.toString)),
      td(),
      td(cls := "ledger-num", if count == 0 then "" else MoneyFormatter.formatSimple(total)),
      td(),
      td(
        cls  := "text-end",
        button(
          tpe             := "button",
          cls             := "btn btn-link btn-sm p-0 fw-normal",
          dataAttr("add") := AccountRole.asString(role),
          "+ Add",
          onClick --> { _ => adding.set(Some(role)) },
        ),
      ),
    )

  private def accountRow(account: Account, primary: Currency, rates: Map[Currency, Double], editing: Var[Option[AccountId]]): HtmlElement = {
    val own = Money(account.balanceCents, account.currency)
    tr(
      td(account.name, Badges.source(account.balanceSource)),
      td(cls := "text-muted small", account.currency.code),
      td(
        cls  := "ledger-num",
        MoneyFormatter.formatBare(account.balanceCents),
        // An amount in another currency has its value in the primary currency below it, as the Dashboard has it the other way round.
        Option.when(account.currency != primary) {
          span(cls := "ledger-sub", "≈ " + MoneyFormatter.formatSimple(MoneyFormatter.convertToPrimary(own, primary, rates)))
        },
      ),
      td(cls := "text-muted small", account.balanceUpdatedAt.fold("-")(Formatting.formatDate)),
      td(
        cls  := "text-end",
        button(tpe := "button", cls := "btn btn-link btn-sm p-0", "Edit", onClick --> { _ => editing.set(Some(account.id)) }),
      ),
    )
  }

  private def currencySelect(current: Currency, currencies: List[Currency], locked: Option[String], onPick: Currency => Unit): HtmlElement =
    select(
      cls := "form-select form-select-sm",
      // The currency of the account stays in the list also when it is not enabled any more.
      (current :: currencies).distinct.map(c => option(value := c.code, selected := (c == current), c.code)),
      locked.map(reason => List[Modifier[HtmlElement]](disabled := true, title := reason)).getOrElse(Nil),
      onChange.mapToValue --> { v => onPick(Currency(v)) },
    )

  private def typeSelect(current: AccountRole, locked: Option[String], onPick: AccountRole => Unit): HtmlElement =
    select(
      cls               := "form-select form-select-sm",
      dataAttr("field") := "type",
      groups.map(r => option(value := AccountRole.asString(r), selected := (r == current), typeLabel(r))),
      locked.map(reason => List[Modifier[HtmlElement]](disabled := true, title := reason)).getOrElse(Nil),
      onChange.mapToValue --> { v => AccountRole.fromString(v).foreach(onPick) },
    )

  /** The row of an account that is open for a change: its name, its currency and its type. The server has the last word on each (the currency of an
    * account with a bank link or a balance history cannot change; the account of a card group stays a spending account), so a field that it is sure
    * to refuse is locked here, with the reason.
    */
  private def editRow(account: Account, currencies: List[Currency], editing: Var[Option[AccountId]]): HtmlElement = {
    var nameRef: org.scalajs.dom.html.Input = null
    val currencyValue                       = Var(account.currency)
    val roleValue                           = Var(account.role)

    val save = Loading.actionGroup(
      "Save",
      () => {
        val name = Option(nameRef).map(_.value.trim).getOrElse("")
        if name.nonEmpty then dataService.updateAccount(account.id, name, currencyValue.now(), roleValue.now()).map(_ => editing.set(None))
        else Future.successful(())
      },
      "btn btn-primary btn-sm",
    )

    val currencyLocked = Option.when(!account.isManual)("The balance comes from the bank in this currency")
    val typeLocked     = Option.when(account.balanceSource == BalanceSource.CardGroup)("The account of a card group stays a spending account")

    tr(
      cls := "table-warning",
      td(
        input(
          cls          := "form-control form-control-sm",
          tpe          := "text",
          defaultValue := account.name,
          onMountCallback(ctx => nameRef = ctx.thisNode.ref),
          onMountFocus,
          save.onEnter,
        ),
      ),
      td(currencySelect(account.currency, currencies, currencyLocked, currencyValue.set)),
      td(typeSelect(account.role, typeLocked, roleValue.set)),
      td(cls := "text-muted small", "Balance: Dashboard"),
      td(
        cls  := "text-end",
        div(
          cls := "btn-group btn-group-sm",
          save.btn,
          button(tpe := "button", cls := "btn btn-secondary btn-sm", "Cancel", onClick --> { _ => editing.set(None) }),
          // An account that a bank link or a card group drives is unlinked first (Banking page); the server refuses to delete it before that.
          Option.when(account.isManual) {
            Loading.confirmActionButton(
              "Del",
              () => dataService.deleteAccount(account.id).map(_ => editing.set(None)),
              hint = "Deletes the account and its balance history",
            )
          },
        ),
      ),
    )
  }

  /** The row for a new account, under the group whose "+ Add" opened it. The type starts as that group and can be changed. */
  private def addRow(role: AccountRole, currencies: List[Currency], primary: Currency, adding: Var[Option[AccountRole]]): HtmlElement = {
    var nameRef: org.scalajs.dom.html.Input = null
    val currencyValue                       = Var(primary)
    val roleValue                           = Var(role)

    val add = Loading.actionGroup(
      "Add",
      () => {
        val name = Option(nameRef).map(_.value.trim).getOrElse("")
        if name.nonEmpty then dataService.addSavingsAccount(name, currencyValue.now(), roleValue.now()).map(_ => adding.set(None))
        else Future.successful(())
      },
      "btn btn-success btn-sm",
    )

    tr(
      cls := "table-primary",
      td(
        input(
          cls         := "form-control form-control-sm",
          tpe         := "text",
          placeholder := "Account name",
          onMountCallback(ctx => nameRef = ctx.thisNode.ref),
          onMountFocus,
          add.onEnter,
        ),
      ),
      td(currencySelect(primary, currencies, None, currencyValue.set)),
      td(typeSelect(role, None, roleValue.set)),
      td(cls := "text-muted small", "Balance: 0"),
      td(
        cls  := "text-end",
        div(
          cls := "btn-group btn-group-sm",
          add.btn,
          button(tpe := "button", cls := "btn btn-secondary btn-sm", "Cancel", onClick --> { _ => adding.set(None) }),
        ),
      ),
    )
  }
}
