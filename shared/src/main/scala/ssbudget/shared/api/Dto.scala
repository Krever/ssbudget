package ssbudget.shared.api

import io.circe.Codec
import ssbudget.shared.model.*

// Request DTOs
final case class CreateAccount(name: String, currency: Currency) derives Codec.AsObject

final case class CreateBalanceSnapshot(accountId: AccountId, amountCents: Long) derives Codec.AsObject

final case class CreateBudgetItem(name: String, itemType: BudgetItemType, estimateCents: Long) derives Codec.AsObject

final case class UpdateBudgetItem(name: String, itemType: BudgetItemType, estimateCents: Long) derives Codec.AsObject

final case class PayBudgetItem(amountCents: Long) derives Codec.AsObject

final case class CreateSavingsAccount(name: String, currency: Currency, plannedMonthly: Option[Long]) derives Codec.AsObject

final case class UpdateSavingsAccount(name: String, currency: Currency, plannedMonthly: Option[Long]) derives Codec.AsObject

final case class UpdateSavingsAccountBalance(newBalance: Long) derives Codec.AsObject

final case class CreateSavingsTransaction(accountId: SavingsAccountId, amount: Long, note: Option[String]) derives Codec.AsObject

// Response DTOs
final case class IdResponse(id: String) derives Codec.AsObject

final case class AccountResponse(account: Account, balance: BalanceSnapshot) derives Codec.AsObject

final case class SavingsTransactionResponse(
    transaction: SavingsTransaction,
    updatedAccount: SavingsAccount,
) derives Codec.AsObject
