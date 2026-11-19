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
}
