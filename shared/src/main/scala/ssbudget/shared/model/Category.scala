package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.StringId

final case class CategoryId(value: String) extends AnyVal

object CategoryId extends StringId[CategoryId]

/** A spending category a transaction can be classified into (Groceries, Fuel, Eating out, …). Independent of budget items; the rule-based classifier
  * (next phase) assigns transactions to these.
  */
final case class Category(
    id: CategoryId,
    name: String,
    color: Option[String],                        // optional hex swatch for the UI
    budgetType: Option[CategoryBudgetType] = None, // None = not a budget; otherwise how its monthly average predicts remaining spend
) derives Codec.AsObject
