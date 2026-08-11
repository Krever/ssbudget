package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.{EnumCodec, StringId}

final case class ExpenseDefId(value: String) extends AnyVal
object ExpenseDefId                          extends StringId[ExpenseDefId]

enum BudgetItemType {
  case PlannedExpense, PlannedIncome
}

object BudgetItemType {
  given Codec[BudgetItemType] = EnumCodec(
    BudgetItemType.values,
    {
      case PlannedExpense => "planned_expense"
      case PlannedIncome  => "planned_income"
    },
    "budget item type",
  )
}

/** A thing you expect to move every period: a bill to pay or an income to receive. `estimateCents` is what you expect it to be — payment progress
  * against it lives per-period in [[ExpenseRecord]].
  */
final case class BudgetItemDefinition(
    id: ExpenseDefId,
    name: String,
    itemType: BudgetItemType,
    estimateCents: Long, // in cents
    currency: Currency,
) derives Codec.AsObject {
  def estimate: Money = Money(estimateCents, currency)
}
