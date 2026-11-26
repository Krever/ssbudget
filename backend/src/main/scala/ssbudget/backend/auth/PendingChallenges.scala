package ssbudget.backend.auth

import cats.effect.{IO, Ref}
import io.circe.parser.parse
import ssbudget.backend.service.AppClock

import java.time.{Duration, Instant}
import java.util.Base64

/** The passkey requests that wait for their answer, each found by its own challenge.
  *
  * A passkey action has two steps: the server gives a random challenge, and the device signs it. Between the two the server must keep the request.
  * Before, it kept ONE request of each kind for the whole server. The step that makes a login challenge is public (nobody is logged in yet), and each
  * call replaced that one request: a call from anybody between the tap and the fingerprint made the login fail, and two devices of the owner that
  * logged in at the same moment did the same to each other.
  *
  * Here each request stays under its challenge. The answer of the device holds the challenge that it signed (in `clientDataJSON`), so the request is
  * found by it and no id has to go to the browser and back. A request can be taken one time, and it is good for a short time only. The number that is
  * kept has a limit, because the public step would otherwise let anybody fill the memory; at the limit the oldest goes.
  *
  * This is not what makes a passkey safe: the library checks the signature against the request. It only decides WHICH request an answer is for.
  */
final class PendingChallenges[A] private (state: Ref[IO, Vector[PendingChallenges.Entry[A]]], clock: AppClock, lifetime: Duration, limit: Int) {

  def put(challenge: String, request: A): IO[Unit] =
    clock.now.flatMap { now =>
      state.update(entries => (live(entries, now) :+ PendingChallenges.Entry(challenge, request, now.plus(lifetime))).takeRight(limit))
    }

  /** The request that waits under this challenge, when there is one and its time is not over. It is removed: an answer counts one time. */
  def take(challenge: String): IO[Option[A]] =
    clock.now.flatMap { now =>
      state.modify { entries =>
        val (found, rest) = live(entries, now).partition(_.challenge == challenge)
        (rest, found.headOption.map(_.request))
      }
    }

  private def live(entries: Vector[PendingChallenges.Entry[A]], now: Instant): Vector[PendingChallenges.Entry[A]] =
    entries.filter(_.until.isAfter(now))
}

object PendingChallenges {

  final private case class Entry[A](challenge: String, request: A, until: Instant)

  /** A browser gives the user about a minute for the passkey prompt; some more for a slow hand. */
  val Lifetime: Duration = Duration.ofMinutes(5)

  /** Each entry is some kilobytes. A real owner has a few at most. */
  val Limit = 1000

  def apply[A](clock: AppClock = AppClock.system, lifetime: Duration = Lifetime, limit: Int = Limit): IO[PendingChallenges[A]] =
    Ref.of[IO, Vector[Entry[A]]](Vector.empty).map(new PendingChallenges(_, clock, lifetime, limit))

  /** The challenge that a device signed, read from the `clientDataJSON` of its answer (base64url of a JSON object). None when the text is not such
    * data.
    */
  def challengeOf(clientDataJson: String): Option[String] =
    scala.util
      .Try(new String(Base64.getUrlDecoder.decode(clientDataJson.replace("=", "")), "UTF-8"))
      .toOption
      .flatMap(parse(_).toOption)
      .flatMap(_.hcursor.get[String]("challenge").toOption)
}
