package ssbudget.backend.service

import cats.effect.IO
import cats.implicits.*
import io.circe.generic.auto.*
import io.circe.parser.decode
import ssbudget.backend.db.Repositories
import ssbudget.shared.api.{ApiError, CurrencySettingsResponse, ExchangeRatesResponse, KnownCurrency}
import ssbudget.shared.model.{Currency, CurrencySetting, ExchangeRate}
import sttp.client3.*

import java.time.Instant

class CurrencyService(repos: Repositories, sttpBackend: SttpBackend[IO, Any]) {

  def getSettings(): IO[CurrencySettingsResponse] = {
    repos.currencySettings.findAll.map { currencies =>
      val available = Currency.knownCurrencies.map { case (code, name) => KnownCurrency(code, name) }
      CurrencySettingsResponse(
        currencies = currencies,
        availableCurrencies = available,
      )
    }
  }

  def enableCurrency(code: String): IO[Either[ApiError, CurrencySetting]] = {
    if !Currency.isKnown(code) then {
      IO.pure(Left(ApiError.notFound(s"Unknown currency code: $code")))
    } else {
      repos.currencySettings.findByCode(code).flatMap {
        case Some(existing) => IO.pure(Right(existing))
        case None           =>
          val name    = Currency.nameFor(code).getOrElse(code)
          val setting = CurrencySetting(Currency(code), name, isPrimary = false, Instant.now())
          repos.currencySettings.create(setting).as(Right(setting))
      }
    }
  }

  def disableCurrency(code: String): IO[Either[ApiError, Unit]] = {
    for {
      settingOpt <- repos.currencySettings.findByCode(code)
      result     <- settingOpt match {
                      case None                               => IO.pure(Left(ApiError.notFound(s"Currency not found: $code")))
                      case Some(setting) if setting.isPrimary => IO.pure(Left(ApiError.invalid("Cannot disable primary currency")))
                      case Some(_)                            =>
                        // Check if currency is in use
                        val currency = Currency(code)
                        for {
                          accountsUse <- repos.accounts.existsWithCurrency(currency)
                          result      <- if accountsUse then {
                                           IO.pure(Left(ApiError.invalid(s"Currency $code is in use by accounts and cannot be disabled")))
                                         } else {
                                           // Other stored rows can still name the currency (the balance history of an account in
                                           // another state, the records of the removed planned items). The database then refuses the
                                           // delete, and the user gets a plain answer.
                                           repos.currencySettings
                                             .delete(code)
                                             .as(Right(()))
                                             .handleError(_ => Left(ApiError.invalid(s"Currency $code is in use by stored records and cannot be disabled")))
                                         }
                        } yield result
                    }
    } yield result
  }

  def setPrimaryCurrency(code: String): IO[Either[ApiError, Unit]] = {
    repos.currencySettings.findByCode(code).flatMap {
      case None    => IO.pure(Left(ApiError.notFound(s"Currency not enabled: $code")))
      case Some(_) => repos.currencySettings.setPrimary(code).as(Right(()))
    }
  }

  def refreshRates(): IO[Either[ApiError, ExchangeRatesResponse]] = {
    for {
      primaryOpt <- repos.currencySettings.findPrimary
      result     <- primaryOpt match {
                      case None          => IO.pure(Left(ApiError.invalid("No primary currency configured")))
                      case Some(primary) =>
                        val baseCurrency = primary.code.code
                        fetchRatesFromFrankfurter(baseCurrency).flatMap {
                          case Left(error)  => IO.pure(Left(error))
                          case Right(rates) =>
                            // Store exchange rates for each currency pair
                            // Frankfurter returns rates like { "EUR": 0.24 } meaning 1 PLN = 0.24 EUR
                            // We need to store the inverse: 1 EUR = 4.17 PLN (rate to convert TO primary)
                            val now = Instant.now()
                            rates.rates.toList
                              .traverse { case (otherCurrency, apiRate) =>
                                val inverseRate  = if apiRate != 0 then 1.0 / apiRate else 0.0
                                val exchangeRate = ExchangeRate.fromDouble(
                                  Currency(otherCurrency),
                                  Currency(baseCurrency),
                                  inverseRate,
                                  now,
                                )
                                repos.exchangeRates.create(exchangeRate)
                              }
                              .as(Right(rates))
                        }
                    }
    } yield result
  }

  private case class FrankfurterResponse(
      base: String,
      date: String,
      rates: Map[String, Double],
  )

  private def fetchRatesFromFrankfurter(baseCurrency: String): IO[Either[ApiError, ExchangeRatesResponse]] = {
    val request = basicRequest
      .get(uri"https://api.frankfurter.dev/v1/latest?base=$baseCurrency")
      .response(asString)

    sttpBackend.send(request).map { response =>
      response.body match {
        case Left(error) => Left(ApiError.failed(s"Failed to fetch rates: $error"))
        case Right(body) =>
          decode[FrankfurterResponse](body) match {
            case Left(error)     => Left(ApiError.failed(s"Failed to parse response: ${error.getMessage}"))
            case Right(response) =>
              Right(
                ExchangeRatesResponse(
                  rates = response.rates,
                  baseCurrency = response.base,
                  fetchedAt = Instant.now(),
                ),
              )
          }
      }
    }
  }

  /** The newest rate of each currency to the primary currency. */
  def latestRates: IO[List[ExchangeRate]] =
    for {
      primaryOpt <- repos.currencySettings.findPrimary
      rates      <- repos.exchangeRates.findLatestTo(primaryOpt.map(_.code).getOrElse(Currency.PLN))
    } yield rates
}
