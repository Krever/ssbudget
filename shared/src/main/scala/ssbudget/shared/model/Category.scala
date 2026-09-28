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
    color: Option[String],                         // optional hex swatch for the UI
    budgetType: Option[CategoryBudgetType] = None, // None = not a budget; otherwise how its monthly figure is drawn down
    budget: CategoryBudget = CategoryBudget(),     // how that monthly figure is derived
    billPayments: Int = 1,                         // payments a Bill expects per period; each one releases its 1/N share of the budget
) derives Codec.AsObject {

  /** As it should be stored: the budget window clamped (see [[CategoryBudget.normalized]]) and the payment count held to
    * 1..[[Category.maxBillPayments]].
    */
  def normalized: Category = copy(budget = budget.normalized, billPayments = billPayments.max(1).min(Category.maxBillPayments))
}

object Category {

  /** The most payments a Bill may expect per period — beyond a weekly one, the category is really Steady or Subscription. */
  val maxBillPayments = 10
}
