package ssbudget.backend.db

import cats.implicits.catsSyntaxEitherId
import doobie.*
import doobie.implicits.*
import ssbudget.shared.model.*

import java.time.Instant

object DoobieMeta {

  // ID types
  given Meta[AccountId]         = Meta[String].timap(AccountId.apply)(_.value)
  given Meta[ExpenseDefId]      = Meta[String].timap(ExpenseDefId.apply)(_.value)
  given Meta[PeriodId]          = Meta[String].timap(PeriodId.apply)(_.value)
  given Meta[ExpenseRecordId]   = Meta[String].timap(ExpenseRecordId.apply)(_.value)
  given Meta[BalanceSnapshotId] = Meta[String].timap(BalanceSnapshotId.apply)(_.value)

  // Enums
  given Meta[Currency] = Meta[String].timap { s =>
    Currency.values.find(_.toString == s).getOrElse(throw new RuntimeException(s"Unknown currency: $s"))
  }(_.toString)

  given Meta[ExpenseType] = Meta[String].tiemap {
    case "planned"   => ExpenseType.Planned.asRight
    case "estimated" => ExpenseType.Estimated.asRight
    case other       => Left(s"Unknown expense type: $other")
  } {
    case ExpenseType.Planned   => "planned"
    case ExpenseType.Estimated => "estimated"
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
