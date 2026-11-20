package ssbudget.backend.analytics

import cats.effect.{IO, Ref}
import cats.effect.testing.scalatest.AsyncIOSpec
import org.http4s.{Method, Request, Status, Uri}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import sttp.capabilities.fs2.Fs2Streams
import sttp.client3.Response as SttpResponse
import sttp.client3.impl.cats.implicits.*
import sttp.client3.testing.{RecordingSttpBackend, SttpBackendStub}
import sttp.model.StatusCode
import ssbudget.backend.auth.SessionService
import ssbudget.backend.db.repository.Session

/** The proxy holds one Metabase session for the life of the process, and Metabase ends a session after some days. */
class AnalyticsProxySessionSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  private val config = MetabaseConfig("http://metabase.test", "user", "password", "secret", "/metabase", "/data/db")

  // Test mode: the app session is not the subject here.
  private val noSessions = new SessionService {
    def createSession(): IO[String]                         = IO.pure("t")
    def validateSession(token: String): IO[Option[Session]] = IO.pure(None)
    def invalidateSession(token: String): IO[Unit]          = IO.unit
    def cleanupExpiredSessions(): IO[Int]                   = IO.pure(0)
    def invalidateOthers(keep: Option[String]): IO[Unit]    = IO.unit
    def confirm(token: String): IO[Unit]                    = IO.unit
    def recentlyConfirmed(token: String): IO[Boolean]       = IO.pure(false)
  }

  /** A Metabase that gives a new token at each login ("token-1", "token-2", ...) and accepts only `valid`. */
  private def metabase(logins: Ref[IO, Int], valid: String) =
    SttpBackendStub[IO, Fs2Streams[IO]](implicitly).whenAnyRequest.thenRespondF { request =>
      if request.uri.path == List("api", "session") then logins.updateAndGet(_ + 1).map(n => SttpResponse.ok(s"""{"id":"token-$n"}"""))
      else {
        val token = request.headers.find(_.is("X-Metabase-Session")).map(_.value)
        val body  = fs2.Stream.emits((if token.contains(valid) then "the page" else "no session").getBytes.toSeq).covary[IO]
        IO.pure(SttpResponse(SttpBackendStub.RawStream(body), if token.contains(valid) then StatusCode.Ok else StatusCode.Unauthorized))
      }
    }

  "a request that Metabase refuses for an ended session is sent again with a new session" in {
    for {
      logins   <- Ref.of[IO, Int](0)
      // The cached token is the first one, and Metabase accepts only the second: the session of the first ended.
      backend   = new RecordingSttpBackend(metabase(logins, valid = "token-2"))
      tokenRef <- Ref.of[IO, Option[String]](None)
      client    = new MetabaseClient(config, backend, tokenRef)
      routes    = AnalyticsProxyRoutes.make(config, client, backend, noSessions, testMode = true).orNotFound
      first    <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString("/metabase/api/card/1")))
      text     <- first.bodyText.compile.string
      second   <- routes.run(Request[IO](Method.GET, Uri.unsafeFromString("/metabase/api/card/2")))
      count    <- logins.get
    } yield {
      first.status shouldBe Status.Ok
      text shouldBe "the page"
      // The new session is kept: the next request does not log in again.
      second.status shouldBe Status.Ok
      count shouldBe 2
    }
  }
}
