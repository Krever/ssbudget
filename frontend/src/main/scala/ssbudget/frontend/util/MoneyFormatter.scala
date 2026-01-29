package ssbudget.frontend.util

import com.raquo.laminar.api.L.*
import ssbudget.shared.model.{Currency, Money}

object MoneyFormatter {

  private val primaryCurrencyVar: Var[Currency]            = Var(Currency.PLN)
  private val exchangeRatesVar: Var[Map[Currency, Double]] = Var(Map.empty)

  /** Initialize the formatter with reactive signals from DataService. Call once during app startup. */
  def init(primaryCurrency: Signal[Currency], exchangeRates: Signal[Map[Currency, Double]]): Unit = {
    // Subscribe to updates (these subscriptions live for the app lifetime)
    import com.raquo.airstream.ownership.OneTimeOwner
    given owner: OneTimeOwner = new OneTimeOwner(() => ())
    primaryCurrency.foreach(primaryCurrencyVar.set)
    exchangeRates.foreach(exchangeRatesVar.set)
  }

  /** Current primary currency (reactive) */
  def primaryCurrency: Signal[Currency] = primaryCurrencyVar.signal

  /** Get current primary currency value (for non-reactive contexts) */
  def primary: Currency = primaryCurrencyVar.now()

  /** Format cents in the primary currency */
  def formatPrimary(cents: Long): HtmlElement = format(cents, primaryCurrencyVar.now())

  /** Format money as a simple string (e.g., "1,234.56 PLN") */
  def formatSimple(money: Money): String = {
    f"${money.amountCents / 100.0}%,.2f ${money.currency.code}"
  }

  /** Format cents as a simple string */
  def formatSimple(cents: Long, currency: Currency): String = {
    formatSimple(Money(cents, currency))
  }

  /** Format money as a Laminar element with currency conversion if needed. */
  def format(money: Money): HtmlElement = {
    formatWithContext(money, primaryCurrencyVar.now(), exchangeRatesVar.now())
  }

  /** Format cents as a Laminar element with currency conversion if needed. */
  def format(cents: Long, currency: Currency): HtmlElement = {
    format(Money(cents, currency))
  }

  /** Reactive format that updates when primary currency or rates change. */
  def formatReactive(money: Signal[Money]): Signal[HtmlElement] = {
    money
      .combineWith(primaryCurrencyVar.signal)
      .combineWith(exchangeRatesVar.signal)
      .map { case (m, primary, rates) => formatWithContext(m, primary, rates) }
  }

  /** Format a Money signal as a reactive child element. */
  def formatChild(money: Signal[Money]): Modifier[HtmlElement] = {
    child <-- formatReactive(money)
  }

  private def formatWithContext(money: Money, primary: Currency, rates: Map[Currency, Double]): HtmlElement = {
    if money.currency == primary then {
      span(cls := "money-primary", formatSimple(money))
    } else {
      val converted = convertToPrimary(money, primary, rates)
      span(
        cls := "money-foreign d-inline-flex flex-column align-items-end",
        span(cls := "money-amount", formatSimple(money)),
        span(cls := "money-equivalent text-muted small", s"~${formatSimple(converted)}"),
      )
    }
  }

  /** Convert money to primary currency using rates map */
  def convertToPrimary(money: Money, primary: Currency, rates: Map[Currency, Double]): Money = {
    if money.currency == primary then money
    else {
      rates.get(money.currency) match {
        case Some(rate) => Money((money.amountCents * rate).toLong, primary)
        case None       => Money(money.amountCents, primary) // No rate available, keep amount (imperfect fallback)
      }
    }
  }

}
