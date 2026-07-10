package ssbudget.frontend.pages

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import ssbudget.frontend.services.ApiClient
import ssbudget.frontend.util.{Formatting, MoneyFormatter}
import ssbudget.shared.api.{
  Aspsp,
  BankConnectionView,
  ConnectBankRequest,
  CreateAccount,
  CreateCardGroup,
  ImportTransactionsRequest,
  LinkAccountRequest,
  LinkCardGroupRequest,
}
import ssbudget.shared.model.{
  Account,
  AccountId,
  AccountRole,
  BalanceSource,
  BankAccountLink,
  BankAccountLinkId,
  BankConnectionId,
  BankLinkTarget,
  CardGroup,
  CardGroupId,
  ConnectionStatus,
  Currency,
}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

object BankingPage {

  def apply(apiClient: ApiClient): HtmlElement = {
    val connectionsVar = Var(List.empty[BankConnectionView])
    val accountsVar    = Var(List.empty[Account])
    val cardGroupsVar  = Var(List.empty[CardGroup])
    val aspspsVar      = Var(List.empty[Aspsp])
    val loadingVar     = Var(true)
    val errorVar       = Var(Option.empty[String])
    val successVar     = Var(Option.empty[String])
    val countryVar     = Var("PL")
    val aspspNameVar   = Var("")
    val connectingVar  = Var(false)
    val syncingVar     = Var(Set.empty[String]) // connection ids currently syncing
    val importingVar   = Var(Set.empty[String]) // connection ids currently importing transactions

    def loadConnections(): Unit =
      apiClient.banking.connections().onComplete {
        case Success(cs) =>
          connectionsVar.set(cs)
          loadingVar.set(false)
        case Failure(ex) =>
          errorVar.set(Some(s"Failed to load connections: ${ex.getMessage}"))
          loadingVar.set(false)
      }

    def loadCardGroups(): Unit =
      apiClient.banking.listCardGroups().onComplete {
        case Success(g)  => cardGroupsVar.set(g)
        case Failure(ex) => errorVar.set(Some(s"Failed to load card groups: ${ex.getMessage}"))
      }

    def loadAccounts(): Unit =
      apiClient.accounts.list().onComplete {
        case Success(a)  => accountsVar.set(a)
        case Failure(ex) => errorVar.set(Some(s"Failed to load accounts: ${ex.getMessage}"))
      }

    def loadAspsps(): Unit =
      apiClient.banking.listAspsps(Some(countryVar.now().trim.toUpperCase)).onComplete {
        case Success(a)  => aspspsVar.set(a)
        case Failure(ex) => errorVar.set(Some(s"Failed to load banks: ${ex.getMessage}"))
      }

    def connect(): Unit = {
      val name = aspspNameVar.now()
      if name.nonEmpty then {
        connectingVar.set(true)
        errorVar.set(None)
        apiClient.banking.connect(ConnectBankRequest(name, countryVar.now().trim.toUpperCase)).onComplete {
          case Success(res) => dom.window.location.href = res.redirectUrl // off to the bank for SCA
          case Failure(ex)  =>
            connectingVar.set(false)
            errorVar.set(Some(s"Failed to start connection: ${ex.getMessage}"))
        }
      }
    }

    def disconnect(id: BankConnectionId): Unit =
      apiClient.banking.disconnect(id).onComplete {
        case Success(_)  => loadConnections(); loadAccounts()
        case Failure(ex) => errorVar.set(Some(s"Failed to disconnect: ${ex.getMessage}"))
      }

    def link(linkId: BankAccountLinkId, target: BankLinkTarget): Unit = {
      errorVar.set(None)
      apiClient.banking.linkAccount(linkId, LinkAccountRequest(target)).onComplete {
        case Success(cs) => connectionsVar.set(cs); loadAccounts()
        case Failure(ex) => errorVar.set(Some(s"Failed to link account: ${ex.getMessage}"))
      }
    }

    def createAndLink(linkId: BankAccountLinkId, defaultName: String, currency: Currency): Unit = {
      errorVar.set(None)
      apiClient.accounts.create(CreateAccount(defaultName, currency, AccountRole.Spending, None)).onComplete {
        case Success(account) =>
          loadAccounts()
          link(linkId, BankLinkTarget.Account(account.id))
        case Failure(ex)      =>
          errorVar.set(Some(s"Failed to create account (${currency.code}): ${ex.getMessage}. Enable the currency in Settings first."))
      }
    }

    def createGroup(name: String, limitCents: Long, currency: String): Unit = {
      errorVar.set(None)
      apiClient.banking.createCardGroup(CreateCardGroup(name, limitCents, Currency(currency))).onComplete {
        case Success(_)  =>
          loadCardGroups()
          loadAccounts()
          successVar.set(Some(s"Card group '$name' created. Assign your cards to it below, then Sync."))
        case Failure(ex) => errorVar.set(Some(s"Failed to create card group: ${ex.getMessage}"))
      }
    }

    def deleteGroup(id: CardGroupId): Unit = {
      errorVar.set(None)
      apiClient.banking.deleteCardGroup(id).onComplete {
        case Success(_)  =>
          loadCardGroups()
          loadConnections()
          loadAccounts()
        case Failure(ex) => errorVar.set(Some(s"Failed to delete card group: ${ex.getMessage}"))
      }
    }

    def linkGroup(id: CardGroupId, accountId: Option[AccountId]): Unit = {
      errorVar.set(None)
      apiClient.banking.linkCardGroup(id, LinkCardGroupRequest(accountId)).onComplete {
        case Success(gs) => cardGroupsVar.set(gs); loadAccounts()
        case Failure(ex) => errorVar.set(Some(s"Failed to link card group: ${ex.getMessage}"))
      }
    }

    def createAndLinkGroup(groupId: CardGroupId, name: String, currency: Currency): Unit = {
      errorVar.set(None)
      apiClient.accounts.create(CreateAccount(name, currency, AccountRole.Spending, None)).onComplete {
        case Success(account) =>
          loadAccounts()
          linkGroup(groupId, Some(account.id))
        case Failure(ex)      => errorVar.set(Some(s"Failed to create account: ${ex.getMessage}"))
      }
    }

    def sync(id: BankConnectionId): Unit = {
      syncingVar.update(_ + id.value)
      errorVar.set(None)
      successVar.set(None)
      apiClient.banking.sync(id).onComplete {
        case Success(cs) =>
          syncingVar.update(_ - id.value)
          connectionsVar.set(cs)
          loadAccounts()
          successVar.set(Some("Balances synced from the bank. Refresh the app to see them on the dashboard."))
        case Failure(ex) =>
          syncingVar.update(_ - id.value)
          errorVar.set(Some(s"Sync failed: ${ex.getMessage}"))
      }
    }

    def importTx(id: BankConnectionId, monthsBack: Option[Int]): Unit = {
      importingVar.update(_ + id.value)
      errorVar.set(None)
      successVar.set(None)
      apiClient.banking.importTransactions(id, ImportTransactionsRequest(monthsBack)).onComplete {
        case Success(res) =>
          importingVar.update(_ - id.value)
          successVar.set(
            Some(s"Imported ${res.totalImported} new transaction(s) (${res.totalSkipped} already had). See the Transactions page."),
          )
        case Failure(ex)  =>
          importingVar.update(_ - id.value)
          errorVar.set(Some(s"Import failed: ${ex.getMessage}"))
      }
    }

    div(
      cls := "container py-4",
      onMountCallback { _ =>
        loadConnections()
        loadCardGroups()
        loadAccounts()
        loadAspsps()
      },
      h2(cls := "mb-4", "Bank Connections"),
      child.maybe <-- errorVar.signal.map(_.map { error =>
        div(
          cls := "alert alert-danger alert-dismissible",
          error,
          button(tpe := "button", cls := "btn-close", onClick --> { _ => errorVar.set(None) }),
        )
      }),
      child.maybe <-- successVar.signal.map(_.map { msg =>
        div(
          cls := "alert alert-success alert-dismissible",
          msg,
          button(tpe := "button", cls := "btn-close", onClick --> { _ => successVar.set(None) }),
        )
      }),
      connectCard(countryVar, aspspNameVar, aspspsVar, connectingVar, loadAspsps, connect),
      cardGroupsCard(cardGroupsVar.signal, connectionsVar.signal, accountsVar.signal, createGroup, deleteGroup, linkGroup, createAndLinkGroup),
      child <-- loadingVar.signal.map { loading =>
        if loading then div(cls := "text-center py-3", div(cls := "spinner-border text-primary"))
        else
          connectionsList(
            connectionsVar.signal,
            accountsVar.signal,
            cardGroupsVar.signal,
            syncingVar,
            importingVar,
            disconnect,
            sync,
            importTx,
            link,
            createAndLink,
          )
      },
    )
  }

  private def connectCard(
      countryVar: Var[String],
      aspspNameVar: Var[String],
      aspspsVar: Var[List[Aspsp]],
      connectingVar: Var[Boolean],
      loadAspsps: () => Unit,
      connect: () => Unit,
  ): HtmlElement =
    div(
      cls := "card mb-4",
      div(cls := "card-header", h5(cls := "mb-0", "Connect a bank")),
      div(
        cls   := "card-body",
        div(
          cls     := "row g-2 align-items-end",
          div(
            cls := "col-auto",
            label(cls   := "form-label small mb-1", "Country"),
            input(
              cls       := "form-control",
              styleAttr := "max-width: 6rem",
              controlled(value <-- countryVar.signal, onInput.mapToValue --> countryVar.writer),
              onBlur --> { _ => loadAspsps() },
            ),
          ),
          div(
            cls := "col",
            label(cls := "form-label small mb-1", "Bank"),
            select(
              cls     := "form-select",
              onChange.mapToValue --> aspspNameVar.writer,
              option(value := "", "Select a bank..."),
              children <-- aspspsVar.signal.map(_.map(a => option(value := a.name, a.name))),
            ),
          ),
          div(
            cls := "col-auto",
            button(
              cls := "btn btn-primary",
              disabled <-- connectingVar.signal.combineWith(aspspNameVar.signal).map { case (connecting, name) => connecting || name.isEmpty },
              child <-- connectingVar.signal.map { connecting =>
                if connecting then span(span(cls := "spinner-border spinner-border-sm me-2"), "Connecting...")
                else span("Connect")
              },
              onClick --> { _ => connect() },
            ),
          ),
        ),
        small(cls := "text-muted mt-2 d-block", "You'll be redirected to your bank to authorize access, then back here."),
      ),
    )

  private def cardGroupsCard(
      groups: Signal[List[CardGroup]],
      connections: Signal[List[BankConnectionView]],
      accounts: Signal[List[Account]],
      createGroup: (String, Long, String) => Unit,
      deleteGroup: CardGroupId => Unit,
      linkGroup: (CardGroupId, Option[AccountId]) => Unit,
      createAndLinkGroup: (CardGroupId, String, Currency) => Unit,
  ): HtmlElement = {
    val nameVar     = Var("")
    val limitVar    = Var("")
    val currencyVar = Var("PLN")
    val formErrorV  = Var(Option.empty[String])

    def submit(): Unit = {
      val name  = nameVar.now().trim
      val cents = parseAmountCents(limitVar.now())
      if name.isEmpty then formErrorV.set(Some("Enter a group name."))
      else if cents.isEmpty then formErrorV.set(Some("Enter a valid limit amount, e.g. 10000."))
      else {
        formErrorV.set(None)
        createGroup(name, cents.get, currencyVar.now().trim.toUpperCase)
        nameVar.set("")
        limitVar.set("")
      }
    }

    div(
      cls := "card mb-4",
      div(cls := "card-header", h5(cls := "mb-0", "Credit card groups (shared limit)")),
      div(
        cls   := "card-body",
        p(
          cls := "text-muted small",
          "Cards that share one credit limit. Remaining limit = sum of the cards' available balances − (cards − 1) × limit. " +
            "Link a group to one of your accounts (or create one) so the remaining limit counts toward your balance; assign your cards below, then Sync.",
        ),
        child <-- groups.combineWith(connections).combineWith(accounts).map { case (gs, conns, accs) =>
          if gs.isEmpty then p(cls := "text-muted mb-3", "No card groups yet.")
          else
            table(
              cls                  := "table table-sm align-middle mb-3",
              thead(
                tr(th("Group"), th(cls := "text-end", "Limit"), th(cls := "text-end", "Remaining"), th("Cards"), th("Mirror account"), th()),
              ),
              tbody(gs.map(g => cardGroupRow(g, conns, accs, deleteGroup, linkGroup, createAndLinkGroup))),
            )
        },
        // New group form
        div(
          cls := "row g-2 align-items-end",
          div(
            cls   := "col",
            label(cls := "form-label small mb-1", "Name"),
            input(cls := "form-control", placeholder := "e.g. PKO cards", controlled(value <-- nameVar.signal, onInput.mapToValue --> nameVar.writer)),
          ),
          div(
            cls   := "col-auto",
            label(cls     := "form-label small mb-1", "Shared limit"),
            input(
              cls         := "form-control",
              styleAttr   := "max-width: 9rem",
              placeholder := "10000",
              controlled(value <-- limitVar.signal, onInput.mapToValue --> limitVar.writer),
            ),
          ),
          div(
            cls   := "col-auto",
            label(cls   := "form-label small mb-1", "Currency"),
            input(
              cls       := "form-control",
              styleAttr := "max-width: 6rem",
              controlled(value <-- currencyVar.signal, onInput.mapToValue --> currencyVar.writer),
            ),
          ),
          div(cls := "col-auto", button(cls := "btn btn-outline-primary", "Add group", onClick --> { _ => submit() })),
        ),
        child.maybe <-- formErrorV.signal.map(_.map(e => div(cls := "text-danger small mt-2", e))),
      ),
    )
  }

  private def cardGroupRow(
      g: CardGroup,
      conns: List[BankConnectionView],
      accounts: List[Account],
      deleteGroup: CardGroupId => Unit,
      linkGroup: (CardGroupId, Option[AccountId]) => Unit,
      createAndLinkGroup: (CardGroupId, String, Currency) => Unit,
  ): HtmlElement = {
    val members    = conns.flatMap(_.accounts).filter(_.target == BankLinkTarget.CardGroupMember(g.id))
    val remaining  = g.remaining(members.flatMap(_.lastBalanceCents))
    val currentAcc = g.accountId.map(_.value).getOrElse("")
    // A group can mirror onto any spending account that isn't already driven by a different group.
    val linkable   = accounts.filter(a => a.role == AccountRole.Spending && (a.balanceSource != BalanceSource.CardGroup || g.accountId.contains(a.id)))
    tr(
      td(strong(g.name)),
      td(cls := "text-end font-monospace", MoneyFormatter.formatSimple(g.limitCents, g.currency)),
      td(cls := "text-end font-monospace", remaining.map(r => MoneyFormatter.formatSimple(r, g.currency)).getOrElse("— sync —")),
      td(s"${members.size}"),
      td(
        div(
          cls := "d-flex gap-2 align-items-center",
          select(
            cls   := "form-select form-select-sm",
            value := currentAcc,
            onChange.mapToValue --> { v => linkGroup(g.id, if v.isEmpty then None else Some(AccountId(v))) },
            option(value := "", "— not linked —"),
            linkable.map(a => option(value := a.id.value, selected := (a.id.value == currentAcc), s"${a.name} (${a.currency.code})")),
          ),
          if g.accountId.isEmpty then button(
            cls := "btn btn-outline-success btn-sm text-nowrap",
            "Create & link",
            onClick --> { _ => createAndLinkGroup(g.id, g.name, g.currency) },
          )
          else emptyNode,
        ),
      ),
      td(cls := "text-end", button(cls := "btn btn-outline-danger btn-sm", "Delete", onClick --> { _ => deleteGroup(g.id) })),
    )
  }

  private def connectionsList(
      connections: Signal[List[BankConnectionView]],
      accounts: Signal[List[Account]],
      cardGroups: Signal[List[CardGroup]],
      syncingVar: Var[Set[String]],
      importingVar: Var[Set[String]],
      disconnect: BankConnectionId => Unit,
      sync: BankConnectionId => Unit,
      importTx: (BankConnectionId, Option[Int]) => Unit,
      link: (BankAccountLinkId, BankLinkTarget) => Unit,
      createAndLink: (BankAccountLinkId, String, Currency) => Unit,
  ): HtmlElement =
    div(
      child <-- connections.combineWith(accounts).combineWith(cardGroups).map { case (conns, accs, groups) =>
        if conns.isEmpty then p(cls := "text-muted", "No banks connected yet.")
        else div(conns.map(v => connectionCard(v, accs, groups, syncingVar, importingVar, disconnect, sync, importTx, link, createAndLink)))
      },
    )

  private def connectionCard(
      view: BankConnectionView,
      accounts: List[Account],
      cardGroups: List[CardGroup],
      syncingVar: Var[Set[String]],
      importingVar: Var[Set[String]],
      disconnect: BankConnectionId => Unit,
      sync: BankConnectionId => Unit,
      importTx: (BankConnectionId, Option[Int]) => Unit,
      link: (BankAccountLinkId, BankLinkTarget) => Unit,
      createAndLink: (BankAccountLinkId, String, Currency) => Unit,
  ): HtmlElement = {
    val conn      = view.connection
    val monthsVar = Var("0") // "0" = incremental (new only); otherwise months to backfill
    div(
      cls := "card mb-3",
      div(
        cls := "card-body",
        div(
          cls := "d-flex justify-content-between align-items-start",
          div(
            h5(cls := "mb-1", conn.aspspName, span(cls := "text-muted small ms-2", conn.aspspCountry)),
            statusBadge(conn.status),
            conn.validUntil.map(v => small(cls := "text-muted ms-2", s"Valid until ${v.toString.take(10)}")).getOrElse(emptyNode),
            view.accounts
              .flatMap(_.lastSyncedAt)
              .maxOption
              .map(t => small(cls := "text-muted ms-2", s"Last synced ${Formatting.formatDateTime(t)}"))
              .getOrElse(emptyNode),
          ),
          div(
            cls := "d-flex flex-column align-items-end gap-2",
            div(
              cls       := "btn-group btn-group-sm",
              button(
                cls      := "btn btn-outline-primary",
                disabled <-- syncingVar.signal.map(_.contains(conn.id.value)),
                child <-- syncingVar.signal.map(_.contains(conn.id.value)).map { syncing =>
                  if syncing then span(span(cls := "spinner-border spinner-border-sm me-1"), "Syncing...")
                  else span("Sync balances")
                },
                onClick --> { _ => sync(conn.id) },
              ),
              button(cls := "btn btn-outline-danger", "Disconnect", onClick --> { _ => disconnect(conn.id) }),
            ),
            div(
              cls       := "input-group input-group-sm",
              styleAttr := "width: auto",
              select(
                cls := "form-select form-select-sm",
                onChange.mapToValue --> monthsVar.writer,
                option(value := "0", "New only"),
                option(value := "1", "1 month"),
                option(value := "3", "3 months"),
                option(value := "6", "6 months"),
                option(value := "12", "12 months"),
              ),
              button(
                cls := "btn btn-outline-secondary text-nowrap",
                disabled <-- importingVar.signal.map(_.contains(conn.id.value)),
                child <-- importingVar.signal.map(_.contains(conn.id.value)).map { importing =>
                  if importing then span(span(cls := "spinner-border spinner-border-sm me-1"), "Importing...")
                  else span("Import tx")
                },
                onClick --> { _ =>
                  val m = monthsVar.now()
                  importTx(conn.id, if m == "0" then None else Some(m.toInt))
                },
              ),
            ),
          ),
        ),
        if view.accounts.isEmpty then emptyNode
        else
          table(
            cls := "table table-sm align-middle mt-3 mb-0",
            thead(tr(th("Account"), th(cls := "text-end", "Balance"), th("Linked app account"))),
            tbody(view.accounts.map(l => accountRow(l, accounts, cardGroups, link, createAndLink))),
          ),
      ),
    )
  }

  // The <select> value is a view-layer token; the wire model is the typed BankLinkTarget.
  private def targetToValue(t: BankLinkTarget): String = t match {
    case BankLinkTarget.Unlinked            => ""
    case BankLinkTarget.Account(id)         => s"acc:${id.value}"
    case BankLinkTarget.CardGroupMember(id) => s"grp:${id.value}"
  }

  private def valueToTarget(v: String): BankLinkTarget =
    if v.startsWith("acc:") then BankLinkTarget.Account(AccountId(v.drop(4)))
    else if v.startsWith("grp:") then BankLinkTarget.CardGroupMember(CardGroupId(v.drop(4)))
    else BankLinkTarget.Unlinked

  private def accountRow(
      l: BankAccountLink,
      accounts: List[Account],
      cardGroups: List[CardGroup],
      link: (BankAccountLinkId, BankLinkTarget) => Unit,
      createAndLink: (BankAccountLinkId, String, Currency) => Unit,
  ): HtmlElement = {
    val title      = l.name.orElse(l.product).getOrElse(l.ebAccountUid)
    val currentVal = targetToValue(l.target)
    // A bank account cannot link to a card-group's own mirror account.
    val linkable   = accounts.filter(_.balanceSource != BalanceSource.CardGroup)

    val balanceCell = (l.lastBalanceCents, l.lastBalanceCurrency) match {
      case (Some(cents), Some(cur)) => span(cls := "font-monospace", MoneyFormatter.formatSimple(cents, cur))
      case (Some(cents), None)      => span(cls := "font-monospace", MoneyFormatter.formatSimple(cents, l.currency.getOrElse(Currency.PLN)))
      case _                        => span(cls := "text-muted", "— not synced —")
    }

    tr(
      td(
        div(strong(title), l.currency.map(c => span(cls := "badge text-bg-secondary ms-2", c.code)).getOrElse(emptyNode)),
        l.product.filter(p => !l.name.contains(p)).map(p => small(cls := "text-muted d-block", p)).getOrElse(emptyNode),
        l.iban.map(v => small(cls := "text-muted d-block font-monospace", v)).getOrElse(emptyNode),
      ),
      td(cls := "text-end", balanceCell),
      td(
        div(
          cls := "d-flex gap-2 align-items-center",
          select(
            cls   := "form-select form-select-sm",
            value := currentVal,
            onChange.mapToValue --> { v => link(l.id, valueToTarget(v)) },
            option(value := "", "— not linked —"),
            linkable.map { a =>
              val v     = targetToValue(BankLinkTarget.Account(a.id))
              val label = if a.role == AccountRole.Savings then s"Savings: ${a.name} (${a.currency.code})" else s"${a.name} (${a.currency.code})"
              option(value := v, selected := (v == currentVal), label)
            },
            cardGroups.map { g =>
              val v = targetToValue(BankLinkTarget.CardGroupMember(g.id))
              option(value := v, selected := (v == currentVal), s"Card group: ${g.name}")
            },
          ),
          if l.target == BankLinkTarget.Unlinked then button(
            cls := "btn btn-outline-success btn-sm text-nowrap",
            "Create & link",
            onClick --> { _ => createAndLink(l.id, title, l.currency.getOrElse(Currency.PLN)) },
          )
          else emptyNode,
        ),
      ),
    )
  }

  private def parseAmountCents(s: String): Option[Long] =
    scala.util.Try((BigDecimal(s.trim.replace(",", ".")) * 100).setScale(0, BigDecimal.RoundingMode.HALF_UP).toLongExact).toOption

  private def statusBadge(status: ConnectionStatus): HtmlElement = {
    val (cls0, label) = status match {
      case ConnectionStatus.Active  => ("text-bg-success", "Active")
      case ConnectionStatus.Pending => ("text-bg-warning", "Pending")
      case ConnectionStatus.Expired => ("text-bg-secondary", "Expired")
      case ConnectionStatus.Revoked => ("text-bg-danger", "Revoked")
    }
    span(cls := s"badge $cls0", label)
  }
}
