package ssbudget.backend.service

import cats.effect.{IO, Ref}
import cats.implicits.*
import io.circe.generic.auto.*
import io.circe.parser.decode
import ssbudget.backend.db.Repositories
import ssbudget.shared.api.{ApiError, CurrencySettingsResponse, ExchangeRatesResponse, KnownCurrency}
import ssbudget.shared.model.{Currency, CurrencySetting, ExchangeRate}
import sttp.client3.*

import java.time.{DayOfWeek, Instant, LocalDate}

class CurrencyService(
    repos: Repositories,
    sttpBackend: SttpBackend[IO, Any],
    clock: AppClock = AppClock.system,
    ratesUrl: String = CurrencyService.sourceUrl,
) {

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
          clock.now.flatMap { now =>
            val name    = Currency.nameFor(code).getOrElse(code)
            val setting = CurrencySetting(Currency(code), name, isPrimary = false, now)
            repos.currencySettings.create(setting).as(Right(setting))
          }
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

  /** Gets the newest rates of the enabled currencies and of the currencies in use, and then the days that are missing in the history (see
    * [[fillHistory]]). The Refresh button of the Settings page and each bank sync call it.
    */
  def refreshRates(): IO[Either[ApiError, ExchangeRatesResponse]] =
    repos.currencySettings.findPrimary.flatMap {
      case None          => IO.pure(Left(ApiError.invalid("No primary currency configured")))
      case Some(primary) =>
        for {
          enabled <- repos.currencySettings.findAll
          used    <- currenciesInUse(primary.code)
          wanted   = used ++ enabled.map(_.code) - primary.code
          fetched <- fetchLatest(primary.code, wanted)
          now     <- clock.now
          result  <- fetched match {
                       case Left(error)   => IO.pure(Left(error))
                       case Right(latest) =>
                         for {
                           _       <- repos.exchangeRates.upsertAll(toPrimary(primary.code, latest.date, latest.rates, now))
                           history <- fillHistory(force = true).attempt
                         } yield history
                           .leftMap(e => ApiError.failed(s"Failed to fetch the history of the rates: ${e.getMessage}"))
                           .as(ExchangeRatesResponse(rates = latest.rates, baseCurrency = latest.base, fetchedAt = now))
                     }
        } yield result
    }

  /** Makes sure that each currency in use has a rate for each business day, from the first day that the app has a record of to the last day that the
    * source has published. It gets the missing days from the source in one request; with no day missing it does nothing.
    *
    * There is no scheduler (the machine sleeps when nobody uses the app), so this runs with the daily record and fills all the days that were missed;
    * the first run fills the whole history. A failed attempt, or one that got nothing new (a holiday), is not made again for an hour, unless `force`.
    * A failure is raised: the caller reports it.
    *
    * @return
    *   the number of rates stored
    */
  def fillHistory(force: Boolean): IO[Int] =
    for {
      table    <- RateTable.load(repos)
      needed   <- currenciesInUse(table.primary)
      firstDay <- firstDayOfRecord
      today    <- clock.today
      count    <- CurrencyService.missingRange(table.days, needed, firstDay.getOrElse(today), today) match {
                    case None               => IO.pure(0)
                    case Some((start, end)) =>
                      attemptIfDue(force) {
                        fetchSeries(table.primary, needed, start, end).flatMap { days =>
                          clock.now.flatMap { now =>
                            val rates = days.toList.flatMap { case (day, rs) => toPrimary(table.primary, day, rs, now) }
                            repos.exchangeRates.upsertAll(rates).as(rates.size)
                          }
                        }
                      }
                  }
    } yield count

  /** The currencies other than `primary` that a figure converts: those of the accounts and those of the bank entries. */
  private def currenciesInUse(primary: Currency): IO[Set[Currency]] =
    for {
      accounts <- repos.accounts.findAll
      inLedger <- repos.bankTransactions.currenciesInUse
    } yield (accounts.map(_.currency) ++ inLedger).toSet - primary

  /** The earliest day that a figure can need a rate for: the start of the first period, the first recorded balance, the first bank entry. */
  private def firstDayOfRecord: IO[Option[LocalDate]] =
    for {
      periods <- repos.periods.findAll
      balance <- repos.balanceSnapshots.firstRecordedAt
      booked  <- repos.bankTransactions.firstBookedDay
    } yield (periods.map(_.startDay) ++ balance.map(PeriodWindow.dayOf) ++ booked).minOption

  private val lastAttempt = Ref.unsafe[IO, Option[Instant]](None)

  private def attemptIfDue(force: Boolean)(work: IO[Int]): IO[Int] =
    clock.now.flatMap { now =>
      lastAttempt
        .modify {
          case Some(previous) if !force && previous.plusSeconds(CurrencyService.retryAfterSeconds).isAfter(now) => (Some(previous), false)
          case _                                                                                                => (Some(now), true)
        }
        .flatMap(due => if due then work else IO.pure(0))
    }

  /** The rates of the source, `1 primary = x other`, as rows `1 other = y primary`. */
  private def toPrimary(base: Currency, day: LocalDate, rates: Map[String, Double], now: Instant): List[ExchangeRate] =
    rates.toList.collect {
      case (other, apiRate) if apiRate > 0 && other != base.code => ExchangeRate.fromDouble(Currency(other), base, 1.0 / apiRate, day, now)
    }

  private case class LatestResponse(base: String, date: LocalDate, rates: Map[String, Double])

  private case class SeriesResponse(base: String, rates: Map[String, Map[String, Double]])

  /** The newest rates of `symbols`. With no symbol there is nothing to ask. */
  private def fetchLatest(base: Currency, symbols: Set[Currency]): IO[Either[ApiError, LatestResponse]] =
    if symbols.isEmpty then clock.today.map(today => Right(LatestResponse(base.code, today, Map.empty)))
    else get[LatestResponse](uri"$ratesUrl/v1/latest?base=${base.code}&symbols=${symbolList(symbols)}")

  /** The rates of `symbols` on each business day in `[start, end]`. The source starts the series at the last business day on or before `start`. */
  private def fetchSeries(base: Currency, symbols: Set[Currency], start: LocalDate, end: LocalDate): IO[Map[LocalDate, Map[String, Double]]] = {
    val range = s"$start..$end"
    get[SeriesResponse](uri"$ratesUrl/v1/$range?base=${base.code}&symbols=${symbolList(symbols)}").flatMap {
      case Left(error)   => IO.raiseError(new RuntimeException(error.message))
      case Right(series) => IO.pure(series.rates.map { case (day, rs) => LocalDate.parse(day) -> rs })
    }
  }

  private def symbolList(symbols: Set[Currency]): String = symbols.toList.map(_.code).sorted.mkString(",")

  private def get[A: io.circe.Decoder](uri: sttp.model.Uri): IO[Either[ApiError, A]] =
    sttpBackend.send(basicRequest.get(uri).response(asString)).map { response =>
      response.body match {
        case Left(error) => Left(ApiError.failed(s"Failed to fetch rates: $error"))
        case Right(body) => decode[A](body).left.map(error => ApiError.failed(s"Failed to parse the rates: ${error.getMessage}"))
      }
    }

  /** The newest rate of each currency to the primary currency. */
  def latestRates: IO[List[ExchangeRate]] =
    for {
      primaryOpt <- repos.currencySettings.findPrimary
      rates      <- repos.exchangeRates.findLatestTo(primaryOpt.map(_.code).getOrElse(Currency.PLN))
    } yield rates
}

object CurrencyService {

  /** Frankfurter: the reference rates of the European Central Bank, one for each business day, from 1999. No key is needed. */
  val sourceUrl = "https://api.frankfurter.dev"

  /** How long a failed or empty attempt to fill the history waits before the next. */
  private val retryAfterSeconds = 3600L

  /** The longest time between two rates that is not a hole: the source has no rate on a weekend and on the holidays of the bank (Easter is a Thursday
    * to a Tuesday).
    */
  private val longestPause = 5

  /** The range of days to get from the source so that each currency of `needed` has a rate for every business day from `firstDay` to the last day
    * that the source has surely published; None when no day is missing. `dates` are the days that have a rate now, for each currency.
    *
    * A currency misses:
    *   - a rate on or before `firstDay` (the first day needs the rate of the last business day before it);
    *   - the days of a pause longer than [[longestPause]] between two rates;
    *   - the days after its last rate, up to the last business day before `today`. The rate of a day comes in the afternoon of that day, so today is
    *     not expected yet.
    *
    * All the missing days, of all currencies, are one range: one request to the source gets them, and a day that is there already is replaced with
    * the same rate.
    */
  def missingRange(
      dates: Map[Currency, Seq[LocalDate]],
      needed: Set[Currency],
      firstDay: LocalDate,
      today: LocalDate,
  ): Option[(LocalDate, LocalDate)] = {
    val lastPublished = lastBusinessDayBefore(today)
    val holes         = needed.toList.flatMap { currency =>
      val days = dates.getOrElse(currency, Nil).filterNot(_.isAfter(today)).distinct.sortBy(_.toEpochDay)
      val head = Option.when(!days.exists(!_.isAfter(firstDay)))((firstDay.minusDays(7), days.headOption.getOrElse(today)))
      val gaps = days.zip(days.drop(1)).collect {
        case (a, b) if b.toEpochDay - a.toEpochDay > longestPause && b.isAfter(firstDay) => (a.plusDays(1), b.minusDays(1))
      }
      val tail = days.lastOption.filter(_.isBefore(lastPublished)).map(last => (last.plusDays(1), today))
      head.toList ++ gaps ++ tail
    }
    Option.when(holes.nonEmpty)((holes.map(_._1).minBy(_.toEpochDay), holes.map(_._2).maxBy(_.toEpochDay)))
  }

  /** The last day before `day` that is not a Saturday or a Sunday. */
  def lastBusinessDayBefore(day: LocalDate): LocalDate =
    Iterator.iterate(day.minusDays(1))(_.minusDays(1)).find(d => d.getDayOfWeek != DayOfWeek.SATURDAY && d.getDayOfWeek != DayOfWeek.SUNDAY).get
}
