package ssbudget.frontend

import io.circe.Codec

sealed trait Page derives Codec.AsObject

object Page {
  case object Dashboard       extends Page
  case object Budget          extends Page
  case object Accounts        extends Page
  case object Periods         extends Page
  case object Banking         extends Page
  case object BankingCallback extends Page
  case object Analytics       extends Page
  case object Settings        extends Page
  case object NotFound        extends Page

  /** Transactions page, with its filters as arguments so a filtered view can be linked to (the category drill-down from the Budget page) and survives
    * a reload. `None` means "whatever the page defaults to", so a bare `/transactions` keeps the triage-first defaults: uncategorized, all months,
    * all accounts, internal transfers hidden.
    */
  case class Transactions(
      category: Option[String] = None, // "all" | "uncategorized" | a category id
      month: Option[String] = None,    // "all" | "current-period" | "previous-period" | "YYYY-MM"
      account: Option[String] = None,  // ebAccountUid
      hideInternal: Option[Boolean] = None,
  ) extends Page

  /** Page identity ignoring arguments — a canonical instance of the same page. Used for nav highlighting and to keep one page instance mounted while
    * only its arguments change. Returning a `Page` rather than a name keeps the next parameterized page honest: a `toString` would carry its
    * arguments and so vary per argument, remounting the very page this exists to hold still.
    */
  def kindOf(page: Page): Page = page match {
    case _: Transactions => Transactions()
    case other           => other
  }
}
