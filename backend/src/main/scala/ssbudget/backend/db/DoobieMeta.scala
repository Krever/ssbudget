package ssbudget.backend.db

import cats.implicits.catsSyntaxEitherId
import doobie.*
import doobie.implicits.*
import ssbudget.shared.model.*

import java.time.Instant

object DoobieMeta {

  // ID types
  given Meta[AccountId]            = Meta[String].timap(AccountId.apply)(_.value)
  given Meta[ExpenseDefId]         = Meta[String].timap(ExpenseDefId.apply)(_.value)
  given Meta[PeriodId]             = Meta[String].timap(PeriodId.apply)(_.value)
  given Meta[ExpenseRecordId]      = Meta[String].timap(ExpenseRecordId.apply)(_.value)
  given Meta[BalanceSnapshotId]    = Meta[String].timap(BalanceSnapshotId.apply)(_.value)
  given Meta[SavingsTransactionId] = Meta[String].timap(SavingsTransactionId.apply)(_.value)
  given Meta[OneTimeExpenseId]     = Meta[String].timap(OneTimeExpenseId.apply)(_.value)
  given Meta[BankConnectionId]     = Meta[String].timap(BankConnectionId.apply)(_.value)
  given Meta[BankAccountLinkId]    = Meta[String].timap(BankAccountLinkId.apply)(_.value)
  given Meta[CardGroupId]          = Meta[String].timap(CardGroupId.apply)(_.value)

  // Value types
  given Meta[Currency] = Meta[String].timap(Currency.apply)(_.code)

  given Meta[ConnectionStatus] = Meta[String].tiemap(ConnectionStatus.fromString)(ConnectionStatus.asString)

  given Meta[AccountRole]   = Meta[String].tiemap(AccountRole.fromString)(AccountRole.asString)
  given Meta[BalanceSource] = Meta[String].tiemap(BalanceSource.fromString)(BalanceSource.asString)

  // BankLinkTarget spans two columns: (link_target_kind, link_target_id).
  given Read[BankLinkTarget] =
    Read[(String, Option[String])].map { case (kind, id) => BankLinkTarget.fromParts(kind, id) }

  given Write[BankLinkTarget] =
    Write[(String, Option[String])].contramap(t => (BankLinkTarget.kind(t), BankLinkTarget.idValue(t)))

  given Meta[BudgetItemType] = Meta[String].tiemap {
    case "planned_expense"   => BudgetItemType.PlannedExpense.asRight
    case "estimated_expense" => BudgetItemType.EstimatedExpense.asRight
    case "planned_income"    => BudgetItemType.PlannedIncome.asRight
    case other               => Left(s"Unknown budget item type: $other")
  } {
    case BudgetItemType.PlannedExpense   => "planned_expense"
    case BudgetItemType.EstimatedExpense => "estimated_expense"
    case BudgetItemType.PlannedIncome    => "planned_income"
  }

  given Meta[EstimateMode] = Meta[String].tiemap {
    case "fixed"      => EstimateMode.Fixed.asRight
    case "last_month" => EstimateMode.LastMonth.asRight
    case "average"    => EstimateMode.Average.asRight
    case other        => Left(s"Unknown estimate mode: $other")
  } {
    case EstimateMode.Fixed     => "fixed"
    case EstimateMode.LastMonth => "last_month"
    case EstimateMode.Average   => "average"
  }

  // Date/Time types (stored as TEXT in SQLite)
  given Meta[Instant] = Meta[String].timap(Instant.parse)(_.toString)
}
