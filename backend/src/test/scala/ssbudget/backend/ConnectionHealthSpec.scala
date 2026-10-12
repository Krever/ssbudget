package ssbudget.backend

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.shared.model.{BankConnection, BankConnectionId, ConnectionHealth, ConnectionStatus}

import java.time.Instant
import java.time.temporal.ChronoUnit

/** When the app asks for a new bank login. The consent end date is fixed by the bank, so the app warns ahead of it and asks for a renewal once it has
  * passed or the bank has closed the session.
  */
class ConnectionHealthSpec extends AnyFreeSpec with Matchers {

  private val now = Instant.parse("2026-10-12T10:00:00Z")

  private def conn(status: ConnectionStatus, validUntil: Option[Instant]): BankConnection =
    BankConnection(BankConnectionId("c-1"), "Wise", "BE", Some("sess"), status, validUntil, None, now)

  private def inDays(d: Long): Option[Instant] = Some(now.plus(d, ChronoUnit.DAYS))

  "An active connection" - {
    "is fine while its consent ends more than a week away" in {
      conn(ConnectionStatus.Active, inDays(30)).health(now) shouldBe ConnectionHealth.Ok
      conn(ConnectionStatus.Active, None).health(now) shouldBe ConnectionHealth.Ok
    }
    "warns with the whole days left inside the last week" in {
      conn(ConnectionStatus.Active, inDays(6)).health(now) shouldBe ConnectionHealth.ExpiresSoon(6)
      conn(ConnectionStatus.Active, Some(now.plusSeconds(3600))).health(now) shouldBe ConnectionHealth.ExpiresSoon(0)
    }
    "needs renewal once its consent end has passed, even if no sync has failed yet" in {
      conn(ConnectionStatus.Active, inDays(-5)).health(now) shouldBe ConnectionHealth.NeedsRenewal
    }
  }

  "A connection the bank has closed needs renewal whatever its end date" in {
    conn(ConnectionStatus.Expired, inDays(30)).health(now) shouldBe ConnectionHealth.NeedsRenewal
    conn(ConnectionStatus.Revoked, inDays(30)).health(now) shouldBe ConnectionHealth.NeedsRenewal
  }

  "A pending authorization is never reported" in {
    conn(ConnectionStatus.Pending, inDays(-5)).health(now) shouldBe ConnectionHealth.Ok
  }
}
