package ssbudget.backend.service

import cats.effect.IO
import cats.implicits.*
import io.circe.generic.auto.*
import io.circe.parser.decode
import org.http4s.client.Client
import org.http4s.{Method, Request, Uri}
import ssbudget.backend.db.Repositories
import ssbudget.shared.api.{CurrencySettingsResponse, ExchangeRatesResponse, KnownCurrency}
import ssbudget.shared.model.{Currency, CurrencySetting, ExchangeRate}

import java.time.Instant

class CurrencyService(repos: Repositories, httpClient: Client[IO]) {

  def getSettings(): IO[CurrencySettingsResponse] = {
    repos.currencySettings.findAll.map { currencies =>
      val available = Currency.knownCurrencies.map { case (code, name) => KnownCurrency(code, name) }
      CurrencySettingsResponse(
        currencies = currencies,
        availableCurrencies = available,
      )
    }
  }

  def enableCurrency(code: String): IO[Either[String, CurrencySetting]] = {
    if !Currency.isKnown(code) then {
      IO.pure(Left(s"Unknown currency code: $code"))
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

  def disableCurrency(code: String): IO[Either[String, Unit]] = {
    for {
      settingOpt <- repos.currencySettings.findByCode(code)
      result     <- settingOpt match {
                      case None                               => IO.pure(Left(s"Currency not found: $code"))
                      case Some(setting) if setting.isPrimary => IO.pure(Left("Cannot disable primary currency"))
                      case Some(_)                            =>
                        // Check if currency is in use
                        val currency = Currency(code)
                        for {
                          accountsUse <- repos.accounts.existsWithCurrency(currency)
                          savingsUse  <- repos.savingsAccounts.existsWithCurrency(currency)
                          result      <- if accountsUse || savingsUse then {
                                           IO.pure(Left(s"Currency $code is in use by accounts and cannot be disabled"))
                                         } else {
                                           repos.currencySettings.delete(code).as(Right(()))
                                         }
                        } yield result
                    }
    } yield result
  }

  def setPrimaryCurrency(code: String): IO[Either[String, Unit]] = {
    repos.currencySettings.findByCode(code).flatMap {
      case None    => IO.pure(Left(s"Currency not enabled: $code"))
      case Some(_) => repos.currencySettings.setPrimary(code).as(Right(()))
    }
  }

  def refreshRates(): IO[Either[String, ExchangeRatesResponse]] = {
    for {
      primaryOpt <- repos.currencySettings.findPrimary
      result     <- primaryOpt match {
                      case None          => IO.pure(Left("No primary currency configured"))
                      case Some(primary) =>
                        val baseCurrency = primary.code.code
                        fetchRatesFromFrankfurter(baseCurrency).flatMap {
                          case Left(error)  => IO.pure(Left(error))
                          case Right(rates) =>
                            // Store exchange rates for each currency pair
                            val now = Instant.now()
                            rates.rates.toList
                              .traverse { case (toCurrency, rate) =>
                                val exchangeRate = ExchangeRate.fromDouble(
                                  Currency(baseCurrency),
                                  Currency(toCurrency),
                                  rate,
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

  private def fetchRatesFromFrankfurter(baseCurrency: String): IO[Either[String, ExchangeRatesResponse]] = {
    val uri     = Uri.unsafeFromString(s"https://api.frankfurter.dev/v1/latest?base=$baseCurrency")
    val request = Request[IO](Method.GET, uri)

    httpClient.expect[String](request).attempt.map {
      case Left(error) => Left(s"Failed to fetch rates: ${error.getMessage}")
      case Right(body) =>
        decode[FrankfurterResponse](body) match {
          case Left(error)     => Left(s"Failed to parse response: ${error.getMessage}")
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
