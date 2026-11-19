package ssbudget.backend.auth

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.backend.AuthRoutes

import java.time.Instant

class LoginThrottleSpec extends AnyFreeSpec with Matchers {

  private val t0 = Instant.parse("2026-11-19T10:00:00Z")

  "The login throttle" - {
    "lets the first failures pass, so a typing error costs nothing" in {
      val throttle = new LoginThrottle(freeFailures = 5)
      (1 to 5).foreach(_ => throttle.failed(t0))
      throttle.lockedFor(t0) shouldBe None
    }

    "locks after one more failure, and for twice as long after each next one, up to the limit" in {
      val throttle = new LoginThrottle(freeFailures = 5, firstLockSeconds = 30, maxLockSeconds = 100)
      (1 to 6).foreach(_ => throttle.failed(t0))
      throttle.lockedFor(t0) shouldBe Some(30L)
      throttle.lockedFor(t0.plusSeconds(29)) shouldBe Some(1L)
      throttle.lockedFor(t0.plusSeconds(30)) shouldBe None
      throttle.failed(t0)
      throttle.lockedFor(t0) shouldBe Some(60L)
      throttle.failed(t0)
      throttle.lockedFor(t0) shouldBe Some(100L)
    }

    "is clear again after a correct password" in {
      val throttle = new LoginThrottle(freeFailures = 5)
      (1 to 8).foreach(_ => throttle.failed(t0))
      throttle.succeeded()
      throttle.lockedFor(t0) shouldBe None
      throttle.failed(t0)
      throttle.lockedFor(t0) shouldBe None
    }
  }

  "The Secure flag of the session cookie" - {
    "is on when every configured origin is https" in {
      AuthRoutes.cookieSecureFor(None, Some("https://budget.example.com")) shouldBe true
      AuthRoutes.cookieSecureFor(None, Some("https://a.example.com, https://b.example.com")) shouldBe true
    }

    "is off for local http, and with no origins configured" in {
      AuthRoutes.cookieSecureFor(None, Some("http://localhost:3000,http://localhost:8080")) shouldBe false
      AuthRoutes.cookieSecureFor(None, Some("https://budget.example.com,http://localhost:3000")) shouldBe false
      AuthRoutes.cookieSecureFor(None, None) shouldBe false
    }

    "follows an explicit setting" in {
      AuthRoutes.cookieSecureFor(Some("true"), None) shouldBe true
      AuthRoutes.cookieSecureFor(Some("FALSE"), Some("https://budget.example.com")) shouldBe false
    }
  }
}
