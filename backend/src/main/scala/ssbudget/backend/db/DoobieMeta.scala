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
  given Meta[SavingsAccountId]     = Meta[String].timap(SavingsAccountId.apply)(_.value)
  given Meta[SavingsTransactionId] = Meta[String].timap(SavingsTransactionId.apply)(_.value)

  // Enums
  given Meta[Currency] = Meta[String].timap { s =>
    Currency.values.find(_.toString == s).getOrElse(throw new RuntimeException(s"Unknown currency: $s"))
  }(_.toString)

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
