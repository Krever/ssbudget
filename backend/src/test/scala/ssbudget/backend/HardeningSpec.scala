package ssbudget.backend

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Length`
import org.http4s.implicits.*
import org.http4s.{Header, HttpRoutes, Method, Request, Status}
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

class HardeningSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers {

  private val routes = HttpRoutes.of[IO] {
    case GET -> Root / "api" / "thing"                => Ok("read")
    case POST -> Root / "api" / "thing"               => Ok("written")
    case POST -> Root / "api" / "database" / "import" => Ok("imported")
  }

  private val app = Hardening(Set("https://budget.example.com"))(routes).orNotFound

  private def request(method: Method, path: String, headers: (String, String)*): Request[IO] =
    Request[IO](method, org.http4s.Uri.unsafeFromString(path)).putHeaders(headers.map { case (k, v) => Header.Raw(CIString(k), v) }*)

  "A write" - {
    "is accepted from the app's own page: the origin is the host of the request" in {
      app
        .run(request(Method.POST, "/api/thing", "Origin" -> "http://127.0.0.1:3002", "Host" -> "127.0.0.1:3002"))
        .asserting(_.status shouldBe Status.Ok)
    }

    "is accepted from an origin the app is configured for" in {
      app
        .run(request(Method.POST, "/api/thing", "Origin" -> "https://budget.example.com", "Host" -> "internal:8080"))
        .asserting(_.status shouldBe Status.Ok)
    }

    "is accepted with no origin (a client that is not a browser)" in {
      app.run(request(Method.POST, "/api/thing", "Host" -> "budget.example.com")).asserting(_.status shouldBe Status.Ok)
    }

    "is refused from another site" in {
      app
        .run(request(Method.POST, "/api/thing", "Origin" -> "https://evil.example.org", "Host" -> "budget.example.com"))
        .asserting(_.status shouldBe Status.Forbidden)
    }
  }

  "A read from another site" - {
    "is not a write, so this rule does not refuse it (the session cookie is not sent with it either)" in {
      app
        .run(request(Method.GET, "/api/thing", "Origin" -> "https://evil.example.org", "Host" -> "budget.example.com"))
        .asserting(_.status shouldBe Status.Ok)
    }
  }

  "A body" - {
    "is refused when it says it is larger than the limit" in {
      val large = request(Method.POST, "/api/thing").putHeaders(`Content-Length`.unsafeFromLong(Hardening.defaultBodyLimit + 1))
      app.run(large).asserting(_.status shouldBe Status.PayloadTooLarge)
    }

    "of that size is accepted by the database import, which has its own limit" in {
      val large = request(Method.POST, "/api/database/import").putHeaders(`Content-Length`.unsafeFromLong(Hardening.defaultBodyLimit + 1))
      app.run(large).asserting(_.status shouldBe Status.Ok)
    }
  }

  "Every answer of the app" - {
    "has the headers that tell the browser what to refuse" in {
      app.run(request(Method.GET, "/api/thing")).asserting { response =>
        def header(name: String) = response.headers.get(CIString(name)).map(_.head.value)
        header("X-Content-Type-Options") shouldBe Some("nosniff")
        header("X-Frame-Options") shouldBe Some("SAMEORIGIN")
        header("Referrer-Policy") shouldBe Some("same-origin")
        header("Content-Security-Policy").get should include("frame-ancestors 'self'")
      }
    }
  }
}
