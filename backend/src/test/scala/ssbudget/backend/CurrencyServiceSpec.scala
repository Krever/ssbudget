package ssbudget.backend

import cats.effect.{IO, Ref}
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.backend.db.Repositories
import ssbudget.backend.db.repository.RepositorySpec
import ssbudget.backend.service.{AppClock, CurrencyService}
import ssbudget.shared.model.*
import sttp.client3.Response as SttpResponse
import sttp.client3.impl.cats.implicits.*
import sttp.client3.testing.SttpBackendStub
import sttp.model.StatusCode

import java.time.{DayOfWeek, Instant, LocalDate}

/** The history of the exchange rates: which days are missing, and how the service gets them from the source. */
class CurrencyServiceSpec extends RepositorySpec {

  private def day(text: String): LocalDate = LocalDate.parse(text)

  private def businessDays(from: LocalDate, to: LocalDate): List[LocalDate] =
    Iterator
      .iterate(from)(_.plusDays(1))
      .takeWhile(!_.isAfter(to))
      .filter(d => d.getDayOfWeek != DayOfWeek.SATURDAY && d.getDayOfWeek != DayOfWeek.SUNDAY)
      .toList

  // Friday 27 November 2026.
  private val friday   = day("2026-11-27")
  private val firstDay = day("2026-11-02")

  "The missing days" - {
    "are all days from a week before the first day, when a currency has no rate" in {
      CurrencyService.missingRange(Map.empty, Set(Currency.EUR), firstDay, friday) shouldBe Some((day("2026-10-26"), friday))
    }

    "are none when each business day has a rate up to the day before today" in {
      val dates      = Map(Currency.EUR -> businessDays(day("2026-10-26"), day("2026-11-26")))
      CurrencyService.missingRange(dates, Set(Currency.EUR), firstDay, friday) shouldBe None
      // On Monday, the last rate that the source has surely published is that of Friday.
      val withFriday = Map(Currency.EUR -> businessDays(day("2026-10-26"), friday))
      CurrencyService.missingRange(withFriday, Set(Currency.EUR), firstDay, day("2026-11-30")) shouldBe None
    }

    "are the days after the last rate" in {
      val dates = Map(Currency.EUR -> businessDays(day("2026-10-26"), day("2026-11-24")))
      CurrencyService.missingRange(dates, Set(Currency.EUR), firstDay, friday) shouldBe Some((day("2026-11-25"), friday))
    }

    "are the days of a pause longer than a long weekend, and one range covers the holes of all currencies" in {
      val withPause = businessDays(day("2026-10-26"), day("2026-11-26")).filterNot(d => d.isAfter(day("2026-11-05")) && d.isBefore(day("2026-11-16")))
      val dates     = Map(Currency.EUR -> withPause, Currency.USD -> businessDays(day("2026-10-26"), day("2026-11-24")))
      CurrencyService.missingRange(dates, Set(Currency.EUR, Currency.USD), firstDay, friday) shouldBe Some((day("2026-11-06"), friday))
      // A holiday on a Friday or a Monday is not a hole.
      val easter    = businessDays(day("2026-10-26"), day("2026-11-26")).filterNot(Set(day("2026-11-13"), day("2026-11-16")))
      CurrencyService.missingRange(Map(Currency.EUR -> easter), Set(Currency.EUR), firstDay, friday) shouldBe None
    }

    "do not count for a currency that is not in use, nor for the time before the first day" in {
      val dates = Map(Currency.EUR -> (day("2026-01-05") :: businessDays(day("2026-10-26"), day("2026-11-26"))))
      CurrencyService.missingRange(dates, Set(Currency.EUR), firstDay, friday) shouldBe None
      CurrencyService.missingRange(dates, Set.empty, firstDay, friday) shouldBe None
    }
  }

  /** A source that answers a series with a rate for each business day of the range, `1 PLN = 0.25 EUR`, and `latest` with the rate of 26 November.
    * `requests` gets the last path segment and the query of each request.
    */
  private def source(requests: Ref[IO, List[String]], fail: Boolean) =
    SttpBackendStub[IO, Any](implicitly).whenAnyRequest.thenRespondF { request =>
      val segment = request.uri.path.last
      requests.update(_ :+ s"$segment?${request.uri.params.toMultiSeq.map { case (k, v) => s"$k=${v.mkString(",")}" }.mkString("&")}").as {
        if fail then SttpResponse("down", StatusCode.ServiceUnavailable)
        else if segment == "latest" then SttpResponse.ok("""{"amount":1.0,"base":"PLN","date":"2026-11-26","rates":{"EUR":0.25}}""")
        else {
          val Array(from, to) = segment.split("\\.\\.")
          val days            = businessDays(day(from), day(to)).map(d => s""""$d":{"EUR":0.25}""").mkString(",")
          SttpResponse.ok(s"""{"amount":1.0,"base":"PLN","start_date":"$from","end_date":"$to","rates":{$days}}""")
        }
      }
    }

  private val now = Instant.parse("2026-11-27T12:00:00Z")

  /** An account in EUR and a period that opened on 2 November, on the new database of the test. */
  private def withService[A](fail: Boolean)(test: (CurrencyService, Repositories, Ref[IO, List[String]]) => IO[A]): IO[A] = {
    val repos = Repositories.fromTransactor(xa)
    val seed  = for {
      _ <- sql"""INSERT INTO accounts (id, name, currency, role, balance_cents, balance_source, balance_updated_at)
                 VALUES ('eur', 'Wise', 'EUR', 'spending', 10000, 'manual', $now)""".update.run
      _ <- sql"""INSERT INTO periods (id, started_at, expected_end, ended_at)
                 VALUES ('p', ${Instant.parse("2026-11-02T08:00:00Z")}, '2026-12-02', NULL)""".update.run
    } yield ()
    for {
      _        <- seed.transact(xa)
      requests <- Ref.of[IO, List[String]](Nil)
      result   <- test(new CurrencyService(repos, source(requests, fail), AppClock.fixed(now), "https://rates.test"), repos, requests)
    } yield result
  }

  "Filling the history" - {
    "gets the missing days of the currencies in use in one request, and then has nothing to get" in {
      withService(fail = false) { (service, repos, requests) =>
        for {
          first   <- service.fillHistory(force = false)
          second  <- service.fillHistory(force = true)
          stored  <- repos.exchangeRates.findAllTo(Currency.PLN)
          browsed <- requests.get
        } yield {
          browsed shouldBe List("2026-10-26..2026-11-27?base=PLN&symbols=EUR")
          first shouldBe businessDays(day("2026-10-26"), friday).size
          second shouldBe 0
          stored.map(_.onDate) shouldBe businessDays(day("2026-10-26"), friday)
          stored.map(_.rate).distinct shouldBe List(40000L)
        }
      }
    }

    "after a failure, does not ask again for an hour unless forced, and raises the failure" in {
      withService(fail = true) { (service, _, requests) =>
        for {
          first   <- service.fillHistory(force = false).attempt
          second  <- service.fillHistory(force = false)
          browsed <- requests.get
        } yield {
          first.isLeft shouldBe true
          second shouldBe 0
          browsed.size shouldBe 1
        }
      }
    }
  }

  "A refresh of the rates" - {
    "stores the newest rates of the currencies in use with the day of the source, and fills the history" in {
      withService(fail = false) { (service, repos, requests) =>
        for {
          result  <- service.refreshRates()
          latest  <- repos.exchangeRates.findLatestTo(Currency.PLN)
          browsed <- requests.get
        } yield {
          result.isRight shouldBe true
          latest.map(r => (r.fromCurrency, r.onDate, r.rate)) shouldBe List((Currency.EUR, day("2026-11-26"), 40000L))
          // The history of EUR was missing up to its newest rate, the one that `latest` gave.
          browsed shouldBe List("latest?base=PLN&symbols=EUR", "2026-10-26..2026-11-26?base=PLN&symbols=EUR")
        }
      }
    }
  }
}
