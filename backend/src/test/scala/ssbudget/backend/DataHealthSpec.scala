package ssbudget.backend

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.backend.service.DataHealth
import ssbudget.shared.model.Currency

class DataHealthSpec extends AnyFreeSpec with Matchers {

  private val pln = Currency("PLN")
  private val eur = Currency("EUR")
  private val isk = Currency("ISK")

  "a currency in use with no rate to the primary currency is a warning" in {
    val warnings = DataHealth.missingRates(pln, List(pln, eur, isk, isk), withRate = Set(eur))
    warnings.map(_.code) shouldBe List("missing-rate")
    warnings.head.message should include("ISK")
  }

  "the primary currency and a currency with a rate give no warning" in {
    DataHealth.missingRates(pln, List(pln, eur), withRate = Set(eur)) shouldBe Nil
  }

  "a background task that fails is a warning until it succeeds again" in {
    import cats.effect.IO
    import cats.effect.unsafe.implicits.global
    import ssbudget.backend.service.BackgroundHealth
    import ssbudget.backend.service.BackgroundHealth.Task

    val program                 = for {
      _      <- BackgroundHealth.reset
      failed <- BackgroundHealth.track(Task.DailyRecord)(IO.raiseError[Unit](new RuntimeException("database is locked"))).attempt
      during <- BackgroundHealth.warnings
      _      <- BackgroundHealth.track(Task.DailyRecord)(IO.unit)
      after  <- BackgroundHealth.warnings
    } yield (failed, during, after)
    val (failed, during, after) = program.unsafeRunSync()

    // The caller still gets the failure, and decides what to answer.
    failed.isLeft shouldBe true
    during.map(_.code) shouldBe List("background")
    during.head.message should include("database is locked")
    after shouldBe Nil
  }
}
