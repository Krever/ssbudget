package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.{EnumCodec, StringId}

final case class ExpenseDefId(value: String) extends AnyVal
object ExpenseDefId                          extends StringId[ExpenseDefId]

enum BudgetItemType {
  case PlannedExpense, EstimatedExpense, PlannedIncome
}

object BudgetItemType {
  given Codec[BudgetItemType] = EnumCodec(
    BudgetItemType.values,
    {
      case PlannedExpense   => "planned_expense"
      case EstimatedExpense => "estimated_expense"
      case PlannedIncome    => "planned_income"
    },
    "budget item type",
  )
}

// Keep ExpenseType as alias for compatibility during transition
type ExpenseType = BudgetItemType
val ExpenseType = BudgetItemType

enum EstimateMode {
  case Fixed, LastMonth, Average
}

object EstimateMode {
  given Codec[EstimateMode] = EnumCodec(
    EstimateMode.values,
    {
      case Fixed     => "fixed"
      case LastMonth => "last_month"
      case Average   => "average"
    },
    "estimate mode",
  )
}

final case class BudgetItemDefinition(
    id: ExpenseDefId,
    name: String,
    itemType: BudgetItemType,
    estimateMode: EstimateMode,
    fixedEstimate: Option[Long], // in cents, only for Fixed mode
    currency: Currency,
) derives Codec.AsObject {
  def estimateMoney: Option[Money] = fixedEstimate.map(cents => Money(cents, currency))
}

// Keep ExpenseDefinition as alias for compatibility
type ExpenseDefinition = BudgetItemDefinition
val ExpenseDefinition = BudgetItemDefinition
