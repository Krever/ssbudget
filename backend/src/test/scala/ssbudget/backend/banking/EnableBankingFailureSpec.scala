package ssbudget.backend.banking

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** A failed bank call must say what went wrong. sttp wraps a transport failure in an exception whose own message names nothing, so the causes below
  * it are what the user reads.
  */
class EnableBankingFailureSpec extends AnyFreeSpec with Matchers {

  "describeFailure" - {
    "includes each cause under the outer exception" in {
      val cause = new java.net.http.HttpTimeoutException("request timed out")
      val outer = new RuntimeException("Exception when sending request: GET https://api.enablebanking.com/accounts/x/transactions", cause)
      EnableBankingClient.describeFailure(outer) shouldBe
        "RuntimeException: Exception when sending request: GET https://api.enablebanking.com/accounts/x/transactions ← " +
        "HttpTimeoutException: request timed out"
    }
    "names the exception class when there is no message" in {
      EnableBankingClient.describeFailure(new java.net.ConnectException()) shouldBe "ConnectException"
    }
  }

  "sessionEnded recognises the codes for a consent the bank has closed" in {
    EnableBankingClient.sessionEnded("""EB GET accounts/u/balances failed: {"code":401,"error":"CLOSED_SESSION"}""") shouldBe true
    EnableBankingClient.sessionEnded("EB GET accounts/u/balances failed: 503 Service Unavailable") shouldBe false
  }
}
