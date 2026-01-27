package ssbudget.frontend

sealed trait Page

object Page {
  case object Dashboard extends Page
  case object Budget    extends Page
  case object Accounts  extends Page
  case object Periods   extends Page
  case object NotFound  extends Page
}
