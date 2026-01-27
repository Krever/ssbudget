package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.{EnumCodec, StringId}

final case class ExpenseDefId(value: String) extends AnyVal
object ExpenseDefId                          extends StringId[ExpenseDefId]

enum ExpenseType {
  case Planned, Estimated
}

object ExpenseType {
  given Codec[ExpenseType] = EnumCodec(
    ExpenseType.values,
    {
      case Planned   => "planned"
      case Estimated => "estimated"
    },
    "expense type",
  )
}

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

final case class ExpenseDefinition(
    id: ExpenseDefId,
    name: String,
    expenseType: ExpenseType,
    estimateMode: EstimateMode,
    fixedEstimate: Option[Long], // in cents, only for Fixed mode
    includeInBalance: Boolean,
) derives Codec.AsObject
