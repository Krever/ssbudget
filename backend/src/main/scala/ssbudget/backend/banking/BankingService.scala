package ssbudget.backend.banking

import cats.effect.IO
import cats.implicits.*
import ssbudget.backend.db.Repositories
import ssbudget.shared.api.*
import ssbudget.shared.model.*

import java.time.Instant
import java.util.UUID

/** Orchestrates the bank connection flow (authorize → callback → session), linking bank accounts to app accounts or card groups, and syncing
  * balances. Bank-reported balances are applied through the single [[Repositories.accounts]] `setBalance` path.
  *
  * [[clientOpt]] is None when the integration is not configured, in which case network operations return a friendly error while the app keeps
  * running.
  */
class BankingService(repos: Repositories, clientOpt: Option[EnableBankingApi]) {

  private val notConfigured = "Enable Banking integration is not configured"

  private def withClient[A](f: EnableBankingApi => IO[Either[String, A]]): IO[Either[String, A]] =
    clientOpt match {
      case Some(client) => f(client)
      case None         => IO.pure(Left(notConfigured))
    }

  def listAspsps(country: Option[String]): IO[Either[String, List[Aspsp]]] =
    withClient(_.listAspsps(country))

  /** Creates a Pending connection with a CSRF state nonce, then asks Enable Banking for the redirect URL. */
  def connect(req: ConnectBankRequest): IO[Either[String, ConnectBankResponse]] =
    withClient { client =>
      for {
        now   <- IO.realTimeInstant
        state  = UUID.randomUUID().toString
        connId = BankConnectionId(UUID.randomUUID().toString)
        conn   = BankConnection(connId, req.aspspName, req.aspspCountry, None, ConnectionStatus.Pending, None, Some(state), now)
        _     <- repos.bankConnections.create(conn)
        res   <- client.startAuthorization(req.aspspName, req.aspspCountry, state)
        out   <- res match {
                   case Right(url) => IO.pure(Right(ConnectBankResponse(url)))
                   case Left(err)  => repos.bankConnections.delete(connId).as(Left(err))
                 }
      } yield out
    }

  /** Validates the returned state against a stored Pending connection, exchanges the code for a session, and persists the (unlinked) accounts. */
  def callback(req: BankCallbackRequest): IO[Either[String, BankConnectionView]] =
    withClient { client =>
      repos.bankConnections.findByAuthState(req.state).flatMap {
        case None       => IO.pure(Left("Unknown or expired authorization state"))
        case Some(conn) =>
          client.createSession(req.code).flatMap {
            case Left(err)      => IO.pure(Left(err))
            case Right(session) =>
              val links   = session.accounts.map { a =>
                BankAccountLink(
                  BankAccountLinkId(UUID.randomUUID().toString),
                  conn.id,
                  a.uid,
                  BankLinkTarget.Unlinked,
                  a.iban,
                  a.name,
                  a.currency.map(Currency.apply),
                  None,
                  None,
                  None,
                  None,
                )
              }
              val updated = conn.copy(
                sessionId = Some(session.sessionId),
                status = ConnectionStatus.Active,
                validUntil = session.validUntil,
                authState = None,
              )
              for {
                _ <- repos.bankConnections.update(updated)
                _ <- links.traverse_(repos.bankConnections.createLink)
              } yield Right(BankConnectionView(updated, links))
          }
      }
    }

  /** Point a bank account link at a new [[BankLinkTarget]] (or `Unlinked`), then reconcile the provenance of both the old and new target accounts.
    * Returns the refreshed connection list.
    */
  def linkAccount(linkId: BankAccountLinkId, req: LinkAccountRequest): IO[Either[String, List[BankConnectionView]]] =
    repos.bankConnections.findLinkById(linkId).flatMap {
      case None           => IO.pure(Left("Bank account link not found"))
      case Some(existing) =>
        validateBankTarget(linkId, existing.currency, req.target).flatMap {
          case Left(err) => IO.pure(Left(err))
          case Right(_)  =>
            val affected = (targetAccountId(existing.target) ++ targetAccountId(req.target)).toList.distinct
            for {
              _     <- repos.bankConnections.updateLinkTarget(linkId, req.target)
              _     <- affected.traverse_(reconcileAccountSource)
              _     <- recomputeCardGroups
              views <- listConnections
            } yield Right(views)
        }
    }

  /** A direct target must reference an existing app account not already driven by a card group or another bank link, and whose currency matches the
    * bank account's (when known) — otherwise the synced amount would be mislabeled.
    */
  private def validateBankTarget(linkId: BankAccountLinkId, linkCurrency: Option[Currency], target: BankLinkTarget): IO[Either[String, Unit]] =
    target match {
      case BankLinkTarget.Unlinked            => IO.pure(Right(()))
      case BankLinkTarget.CardGroupMember(id) =>
        repos.cardGroups.findById(id).map(o => Either.cond(o.isDefined, (), "Card group not found"))
      case BankLinkTarget.Account(id)         =>
        for {
          acctOpt   <- repos.accounts.findById(id)
          groupOpt  <- repos.cardGroups.findByAccount(id)
          otherLink <- repos.bankConnections.findLinkByAccount(id)
        } yield acctOpt match {
          case None                                            => Left("App account not found")
          case Some(_) if groupOpt.isDefined                   => Left("That account already mirrors a card group; pick another.")
          case Some(_) if otherLink.exists(_.id != linkId)     => Left("That account is already linked to another bank account.")
          case Some(a) if linkCurrency.exists(_ != a.currency) =>
            Left(s"Currency mismatch: the bank account is ${linkCurrency.get.code} but the app account is ${a.currency.code}.")
          case Some(_)                                         => Right(())
        }
    }

  private def targetAccountId(target: BankLinkTarget): Option[AccountId] = target match {
    case BankLinkTarget.Account(id) => Some(id)
    case _                          => None
  }

  /** Derive an account's balance provenance from the source-of-truth tables: a card group mirror wins, else a bank link, else Manual (user-editable).
    * Called after any link/group mutation so `balanceSource` can never drift from the links.
    */
  private def reconcileAccountSource(accountId: AccountId): IO[Unit] =
    for {
      groupOpt <- repos.cardGroups.findByAccount(accountId)
      linkOpt  <- repos.bankConnections.findLinkByAccount(accountId)
      source    = if groupOpt.isDefined then BalanceSource.CardGroup
                  else if linkOpt.isDefined then BalanceSource.Bank
                  else BalanceSource.Manual
      _        <- repos.accounts.setBalanceSource(accountId, source)
    } yield ()

  def listCardGroups: IO[List[CardGroup]] = repos.cardGroups.findAll

  /** Creates an (initially unlinked) card group. Link it to an app account afterwards via [[linkCardGroup]] to feed the budget. */
  def createCardGroup(req: CreateCardGroup): IO[Either[String, CardGroup]] = {
    val group = CardGroup(CardGroupId(UUID.randomUUID().toString), req.name, req.limitCents, req.currency, None)
    repos.cardGroups.create(group).as(Right(group))
  }

  /** Links (or unlinks, when accountId is None) a card group to a user-chosen app account whose balance mirrors the remaining limit. Linking makes
    * that account CardGroup-driven; unlinking (or re-linking elsewhere) reverts the previous account to Manual so the user can edit it again.
    */
  def linkCardGroup(id: CardGroupId, req: LinkCardGroupRequest): IO[Either[String, List[CardGroup]]] =
    repos.cardGroups.findById(id).flatMap {
      case None        => IO.pure(Left("Card group not found"))
      case Some(group) =>
        validateCardGroupAccount(id, group.currency, req.accountId).flatMap {
          case Left(err) => IO.pure(Left(err))
          case Right(_)  =>
            val affected = (group.accountId ++ req.accountId).toList.distinct
            for {
              _  <- repos.cardGroups.setAccount(id, req.accountId)
              _  <- affected.traverse_(reconcileAccountSource)
              _  <- recomputeCardGroups
              gs <- listCardGroups
            } yield Right(gs)
        }
    }

  /** The mirror account must exist, not already be driven by another group or a bank link, and share the group's currency. */
  private def validateCardGroupAccount(groupId: CardGroupId, groupCurrency: Currency, accountId: Option[AccountId]): IO[Either[String, Unit]] =
    accountId match {
      case None        => IO.pure(Right(()))
      case Some(accId) =>
        for {
          acctOpt    <- repos.accounts.findById(accId)
          otherGroup <- repos.cardGroups.findByAccount(accId)
          linkOpt    <- repos.bankConnections.findLinkByAccount(accId)
        } yield acctOpt match {
          case None                                          => Left("App account not found")
          case Some(_) if otherGroup.exists(_.id != groupId) => Left("That account already mirrors another card group.")
          case Some(_) if linkOpt.isDefined                  => Left("That account is already linked to a bank account.")
          case Some(a) if a.currency != groupCurrency        =>
            Left(s"Currency mismatch: the card group is ${groupCurrency.code} but the app account is ${a.currency.code}.")
          case Some(_)                                       => Right(())
        }
    }

  /** Removes a card group: detaches member links, deletes the group, then reconciles the previously-linked account (back to Manual unless a bank link
    * still drives it). The account itself is left intact.
    */
  def deleteCardGroup(id: CardGroupId): IO[Either[String, Unit]] =
    repos.cardGroups.findById(id).flatMap {
      case None        => IO.pure(Left("Card group not found"))
      case Some(group) =>
        for {
          _ <- repos.bankConnections.clearCardGroupMembers(id)
          _ <- repos.cardGroups.delete(id)
          _ <- group.accountId.traverse_(reconcileAccountSource)
        } yield Right(())
    }

  /** Recomputes every linked card group's remaining limit from members' last-synced available balances (same currency as the group) and mirrors it
    * onto the linked account: remaining = sum(member available) - (memberCount - 1) * limit.
    */
  private def recomputeCardGroups: IO[Unit] =
    for {
      now    <- IO.realTimeInstant
      groups <- repos.cardGroups.findAll
      _      <- groups.traverse_ { group =>
                  group.accountId match {
                    case None            => IO.unit
                    case Some(accountId) =>
                      for {
                        acctOpt <- repos.accounts.findById(accountId)
                        members <- repos.bankConnections.findLinksByCardGroup(group.id)
                        // Only sum members whose reported balance is in the group's currency; mixed-currency members are ignored.
                        amounts  = members.flatMap(m => m.lastBalanceCents.filter(_ => m.lastBalanceCurrency.forall(_ == group.currency)))
                        _       <- (acctOpt, group.remaining(amounts)) match {
                                     // Only write when the mirror account shares the group's currency (validated at link time).
                                     case (Some(acc), Some(remaining)) if acc.currency == group.currency =>
                                       repos.accounts.setBalance(accountId, remaining, BalanceSource.CardGroup, now)
                                     case _                                                              => IO.unit
                                   }
                      } yield ()
                  }
                }
    } yield ()

  /** The only operation that queries the bank: fetch details + current balance for every account of a connection, cache them on each link, and apply
    * the balance to a directly-linked account. Card-group members feed [[recomputeCardGroups]]. Returns the refreshed connection list.
    */
  def sync(connectionId: BankConnectionId): IO[Either[String, List[BankConnectionView]]] =
    withClient { client =>
      repos.bankConnections.findById(connectionId).flatMap {
        case None                                 => IO.pure(Left("Connection not found"))
        case Some(conn) if conn.sessionId.isEmpty => IO.pure(Left("Connection is not authorized yet"))
        case Some(_)                              =>
          for {
            now   <- IO.realTimeInstant
            links <- repos.bankConnections.findLinksByConnection(connectionId)
            _     <- links.traverse_(link => syncLink(client, link, now))
            _     <- recomputeCardGroups // card-group mirror accounts depend on freshly-synced member balances
            views <- listConnections
          } yield Right(views)
      }
    }

  private def syncLink(client: EnableBankingApi, link: BankAccountLink, now: Instant): IO[Unit] =
    for {
      detailsE <- client.getAccountDetails(link.ebAccountUid)
      balanceE <- client.getBalances(link.ebAccountUid)
      details   = detailsE.toOption
      balance   = balanceE.toOption
      // Merge fetched values over stored ones (never regress good data to None on a failed fetch).
      updated   = link.copy(
                    name = details.flatMap(_.name).orElse(link.name),
                    iban = details.flatMap(_.iban).orElse(link.iban),
                    product = details.flatMap(_.product).orElse(link.product),
                    currency = details.flatMap(_.currency).map(Currency.apply).orElse(balance.map(b => Currency(b.currency))).orElse(link.currency),
                    lastBalanceCents = balance.map(_.amountCents).orElse(link.lastBalanceCents),
                    lastBalanceCurrency = balance.map(b => Currency(b.currency)).orElse(link.lastBalanceCurrency),
                    lastSyncedAt = balance.map(_ => now).orElse(link.lastSyncedAt),
                  )
      _        <- repos.bankConnections.updateLinkSynced(updated)
      _        <- (link.target, balance) match {
                    case (BankLinkTarget.Account(accountId), Some(bal)) =>
                      // Apply only when currencies agree, so we never store a foreign amount under the account's currency.
                      repos.accounts.findById(accountId).flatMap {
                        case Some(acc) if acc.currency == Currency(bal.currency) =>
                          repos.accounts.setBalance(accountId, bal.amountCents, BalanceSource.Bank, now)
                        case _                                                   => IO.unit
                      }
                    case _                                              => IO.unit
                  }
    } yield ()

  def listConnections: IO[List[BankConnectionView]] =
    for {
      conns <- repos.bankConnections.findAll
      views <- conns.traverse { c =>
                 repos.bankConnections.findLinksByConnection(c.id).map(links => BankConnectionView(c, links))
               }
    } yield views

  /** Best-effort revoke at the bank, delete local rows, then reconcile every previously-linked account (back to Manual) and recompute card groups. */
  def disconnect(id: BankConnectionId): IO[Either[String, Unit]] =
    repos.bankConnections.findById(id).flatMap {
      case None       => IO.pure(Left("Connection not found"))
      case Some(conn) =>
        val revoke = (clientOpt, conn.sessionId) match {
          case (Some(client), Some(sid)) => client.deleteSession(sid).attempt.void
          case _                         => IO.unit
        }
        for {
          links      <- repos.bankConnections.findLinksByConnection(id)
          targetAccts = links.flatMap(l => targetAccountId(l.target)).distinct
          _          <- revoke
          _          <- repos.bankTransactions.deleteByConnection(id)
          _          <- repos.bankConnections.deleteLinksByConnection(id)
          _          <- repos.bankConnections.delete(id)
          _          <- targetAccts.traverse_(reconcileAccountSource)
          _          <- recomputeCardGroups
        } yield Right(())
    }
}
