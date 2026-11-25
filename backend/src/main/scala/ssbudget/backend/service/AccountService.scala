package ssbudget.backend.service

import cats.effect.IO
import cats.implicits.*
import ssbudget.backend.db.Repositories
import ssbudget.shared.api.*
import ssbudget.shared.model.*

import java.util.UUID

/** The accounts of the user: to make one, to change its name, currency and type, to set its balance by hand, and to delete it.
  *
  * The rules of an account are here and not in the route file: which type an account can take, when its currency can still change, and which accounts
  * are driven by a bank and so cannot be edited or deleted by hand.
  */
class AccountService(repos: Repositories, clock: AppClock = AppClock.system) {

  private type Result[A] = IO[Either[ApiError, A]]

  def create(dto: CreateAccount): Result[Account] = clock.now.flatMap { now =>
    val accountId = AccountId(UUID.randomUUID().toString)
    val account   = Account(accountId, dto.name, dto.currency, dto.role, 0L, BalanceSource.Manual, Some(now))
    // With the opening zero balance on record: without it, the first balance set later becomes the account's period-start baseline, and
    // money put into a new account never shows as a change in the period it was added.
    repos.accounts.createWithOpeningBalance(account, now).as(Right(account))
  }

  def update(id: AccountId, dto: UpdateAccount): Result[Account] = {
    for {
      existingOpt <- repos.accounts.findById(id)
      result      <- existingOpt match {
                       case Some(existing) =>
                         val role = dto.role.getOrElse(existing.role)
                         // The account of a card group is the remaining limit of the cards: that is spendable money and nothing else.
                         if role != existing.role && existing.balanceSource == BalanceSource.CardGroup then {
                           IO.pure(Left(ApiError.invalid("The account of a card group stays a spending account")))
                         } else {
                           val updated = existing.copy(name = dto.name, currency = dto.currency, role = role)
                           // Only an investment account has transfer categories.
                           val unlink  = IO.whenA(role != AccountRole.Investment)(repos.categories.clearTransferAccount(id))
                           val save    = (repos.accounts.update(updated) *> unlink).as(Right(updated))
                           if dto.currency == existing.currency then save
                           else {
                             // The recorded balances and the bank entries of the account are amounts in its currency. A new currency would
                             // give them a new meaning with no conversion. It is thus possible only while the account is still empty.
                             (repos.balanceSnapshots.findByAccount(id), repos.bankConnections.findLinkByAccount(id)).flatMapN { (history, link) =>
                               if link.isDefined then IO.pure(
                                 Left(ApiError.invalid("The currency cannot change: the account is linked to a bank account")),
                               )
                               else if existing.balanceCents != 0 || history.exists(_.amount != 0) then IO.pure(
                                 Left(
                                   ApiError.invalid("The currency cannot change: the account has a balance history in its currency. Add a new account."),
                                 ),
                               )
                               else save
                             }
                           }
                         }
                       case None           => IO.pure(Left(ApiError.notFound(s"Account not found: ${id.value}")))
                     }
    } yield result
  }

  def updateBalance(id: AccountId, dto: UpdateAccountBalance): Result[Account] = {
    for {
      existingOpt <- repos.accounts.findById(id)
      result      <- existingOpt match {
                       case None                                 =>
                         IO.pure(Left(ApiError.notFound(s"Account not found: ${id.value}")))
                       case Some(existing) if !existing.isManual =>
                         IO.pure(Left(ApiError.invalid("This account's balance is driven by a bank sync and cannot be edited manually")))
                       case Some(existing)                       =>
                         clock.now.flatMap { now =>
                           repos.accounts
                             .setBalance(id, dto.newBalanceCents, BalanceSource.Manual, now)
                             .as(Right(existing.copy(balanceCents = dto.newBalanceCents, balanceUpdatedAt = Some(now))))
                         }
                     }
    } yield result
  }

  def delete(id: AccountId): Result[Unit] = {
    for {
      accOpt <- repos.accounts.findById(id)
      result <- accOpt match {
                  case Some(a) if !a.isManual =>
                    IO.pure(Left(ApiError.invalid("This account's balance is driven by a bank link or card group; unlink it first, then delete.")))
                  case _                      =>
                    repos.accounts.deleteWithHistory(id).as(Right(()))
                }
    } yield result
  }
}
