package ssbudget.frontend

sealed trait Page

object Page {
  case object Dashboard       extends Page
  case object Budget          extends Page
  case object Accounts        extends Page
  case object Periods         extends Page
  case object OneTimeExpenses extends Page
  case object Banking         extends Page
  case object BankingCallback extends Page
  case object Transactions    extends Page
  case object Analytics       extends Page
  case object Settings        extends Page
  case object NotFound        extends Page
}
