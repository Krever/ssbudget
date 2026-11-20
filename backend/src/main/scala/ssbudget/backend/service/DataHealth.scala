package ssbudget.backend.service

import cats.effect.IO
import ssbudget.backend.db.Repositories
import ssbudget.shared.api.DataWarning
import ssbudget.shared.model.Currency

/** Faults in the data that make a figure of the app wrong, and that the app cannot repair itself. Each is told to the user in plain words: a wrong
  * figure with no sign is worse than a warning.
  */
object DataHealth {

  def warnings(repos: Repositories): IO[List[DataWarning]] =
    for {
      primaryOpt <- repos.currencySettings.findPrimary
      primary     = primaryOpt.map(_.code).getOrElse(Currency.PLN)
      accounts   <- repos.accounts.findAll
      inLedger   <- repos.bankTransactions.currenciesInUse
      rates      <- repos.exchangeRates.findLatestTo(primary)
      background <- BackgroundHealth.warnings
    } yield missingRates(primary, accounts.map(_.currency) ++ inLedger, rates.map(_.fromCurrency).toSet) ++ background

  /** A currency with no rate is counted 1 to 1 in every sum (see `BudgetQueries.primaryConverter`). */
  def missingRates(primary: Currency, inUse: List[Currency], withRate: Set[Currency]): List[DataWarning] =
    inUse.distinct.filter(c => c != primary && !withRate(c)).sortBy(_.code).map { c =>
      DataWarning(
        "missing-rate",
        s"No exchange rate from ${c.code} to ${primary.code}. Amounts in ${c.code} are counted as ${primary.code}, 1 to 1. " +
          "Refresh the rates in Settings.",
      )
    }
}
