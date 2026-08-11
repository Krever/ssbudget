package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.EnumCodec

/** How a category's monthly budget predicts the money still to move before the next paycheck. Set per category (None = not a budget):
  *   - [[Steady]]: time-based (groceries, restaurants, fuel). Reserve the remaining-time share of the budget; overspending never zeroes it out ("you
  *     still have to eat").
  *   - [[Bill]]: one payment per period (kindergarten, rent). Reserve the full expected amount until a payment lands, then 0 (regardless of the exact
  *     amount).
  *   - [[Subscription]]: fixed pool (subscriptions). Reserve `budget − spent`; not time-sensitive — pay them all early and nothing more is reserved.
  *
  * All three work in either direction: they are given magnitudes in the budget's own direction, so for a category whose money flows IN — rent from a
  * tenant, a recurring refund, a side income — the very same formulas predict what is still expected to ARRIVE. See [[remaining]].
  */
enum CategoryBudgetType {
  case Steady, Bill, Subscription
}

object CategoryBudgetType {

  def asString(t: CategoryBudgetType): String = t match {
    case Steady       => "steady"
    case Bill         => "bill"
    case Subscription => "subscription"
  }

  def fromString(s: String): Either[String, CategoryBudgetType] = s match {
    case "steady"       => Right(Steady)
    case "bill"         => Right(Bill)
    case "subscription" => Right(Subscription)
    case other          => Left(s"Unknown category budget type: $other")
  }

  /** How much of a budget is still expected to move before the next paycheck. Works in MAGNITUDES only — `budgetCents` and `movedCents` are both
    * counted in the budget's own direction (spent for an expense, received for an income), and the result is what's left in that same direction.
    *
    * Direction is deliberately not this function's business: [[ssbudget.shared.api.CategorySummary]] owns the one rule for which way a category's
    * money flows and converts on the way in and out, so the formulas here read the same for a bill you owe and for rent a tenant owes you.
    */
  def remaining(t: CategoryBudgetType, budgetCents: Long, movedCents: Long, elapsed: Double): Long = {
    val e      = math.max(0.0, math.min(1.0, elapsed))
    val budget = math.max(0L, budgetCents)
    t match {
      case Steady       => math.max(0L, ((1.0 - e) * budget).toLong) // remaining-time share of the budget; independent of overspend
      case Bill         => if movedCents > 0 then 0L else budget     // settled by any movement this period
      case Subscription => math.max(0L, budget - movedCents)         // fixed pool: draw it down to zero
    }
  }

  given Codec[CategoryBudgetType] = EnumCodec(values, asString, "category budget type")
}
