package ssbudget.backend.auth

import cats.effect.{IO, Ref}
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import ssbudget.backend.service.AppClock

import java.time.{Duration, Instant}
import java.util.Base64

class PendingChallengesSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  private val start = Instant.parse("2026-03-01T10:00:00Z")

  "a new challenge does not take the place of one that still waits" in {
    // The fault of before: the login of the owner waits for the fingerprint, and a second "start" (a stranger, or a second device) comes in.
    for {
      pending <- PendingChallenges[String]()
      _       <- pending.put("challenge-of-the-owner", "owner")
      _       <- pending.put("challenge-of-a-stranger", "stranger")
      owner   <- pending.take("challenge-of-the-owner")
      again   <- pending.take("challenge-of-the-owner")
      other   <- pending.take("challenge-of-a-stranger")
    } yield {
      owner shouldBe Some("owner")
      // An answer counts one time.
      again shouldBe None
      other shouldBe Some("stranger")
    }
  }

  "a challenge that nobody made finds nothing" in {
    PendingChallenges[String]().flatMap(_.take("made-up")).asserting(_ shouldBe None)
  }

  "a challenge is good for a short time only" in {
    for {
      time    <- Ref.of[IO, Instant](start)
      pending <- PendingChallenges[String](AppClock(time.get), lifetime = Duration.ofMinutes(5))
      _       <- pending.put("a", "first")
      _       <- pending.put("b", "second")
      _       <- time.update(_.plus(Duration.ofMinutes(5)).minusSeconds(1))
      inTime  <- pending.take("a")
      _       <- time.update(_.plusSeconds(2))
      late    <- pending.take("b")
    } yield {
      inTime shouldBe Some("first")
      late shouldBe None
    }
  }

  "the number that is kept has a limit, and the oldest goes first" in {
    for {
      pending <- PendingChallenges[Int](limit = 3)
      _       <- (1 to 5).toList.foldLeft(IO.unit)((io, n) => io *> pending.put(s"c$n", n))
      oldest  <- pending.take("c1")
      newest  <- pending.take("c5")
      third   <- pending.take("c3")
    } yield {
      oldest shouldBe None
      newest shouldBe Some(5)
      third shouldBe Some(3)
    }
  }

  "the challenge is read from the client data of an answer" in {
    val json    = """{"type":"webauthn.get","challenge":"abc-DEF_123","origin":"https://example.org"}"""
    val encoded = Base64.getUrlEncoder.withoutPadding().encodeToString(json.getBytes("UTF-8"))
    IO {
      PendingChallenges.challengeOf(encoded) shouldBe Some("abc-DEF_123")
      PendingChallenges.challengeOf("not base64 !!") shouldBe None
      PendingChallenges.challengeOf(Base64.getUrlEncoder.encodeToString("not json".getBytes("UTF-8"))) shouldBe None
    }
  }
}
