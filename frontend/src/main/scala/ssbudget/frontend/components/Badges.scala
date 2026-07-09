package ssbudget.frontend.components

import com.raquo.laminar.api.L.*
import ssbudget.shared.model.BalanceSource

object Badges {

  /** A small muted badge showing where an account's balance comes from (empty for manual accounts). */
  def source(source: BalanceSource): Node = source match {
    case BalanceSource.Bank      => span(cls := "badge text-bg-light text-muted ms-2", "bank")
    case BalanceSource.CardGroup => span(cls := "badge text-bg-light text-muted ms-2", "card")
    case BalanceSource.Manual    => emptyNode
  }
}
