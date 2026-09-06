package ssbudget.shared.model

import io.circe.Codec
import ssbudget.shared.json.EnumCodec

/** Which statistic derives a category's monthly figure. The rest of the recipe — how far back to look, or the figure to use instead — lives on
  * [[CategoryBudget]], which is also where the arithmetic is.
  *   - [[Average]]: mean of the monthly history (the original behaviour).
  *   - [[Median]]: middle month of the history — ignores the one holiday month that doubles a mean.
  *   - [[Fixed]]: a figure you type; history is not consulted at all.
  */
enum CategoryBudgetMethod {
  case Average, Median, Fixed
}

object CategoryBudgetMethod {

  def asString(m: CategoryBudgetMethod): String = m match {
    case Average => "average"
    case Median  => "median"
    case Fixed   => "fixed"
  }

  def fromString(s: String): Either[String, CategoryBudgetMethod] = s match {
    case "average" => Right(Average)
    case "median"  => Right(Median)
    case "fixed"   => Right(Fixed)
    case other     => Left(s"Unknown category budget method: $other")
  }

  /** Human label, as the UI shows it. Here so a new method is named once, next to the cases. */
  def label(m: CategoryBudgetMethod): String = m match {
    case Average => "Average"
    case Median  => "Median"
    case Fixed   => "Fixed"
  }

  /** The abbreviated form, for [[CategoryBudget.summary]]. */
  def shortLabel(m: CategoryBudgetMethod): String = if m == Average then "avg" else asString(m)

  given Codec[CategoryBudgetMethod] = EnumCodec(values, asString, "category budget method")
}
