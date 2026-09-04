package ssbudget.backend.analytics

import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import sttp.client3.*
import sttp.model.{Method, StatusCode, Uri}

/** Talks to Metabase's REST API as the service account.
  *
  * Metabase accepts a session token either as its `metabase.SESSION` cookie or as an `X-Metabase-Session` header. We use the header, which means the
  * token stays server-side: the browser holds only the app's own session cookie, and the proxy attaches this one on the way through.
  *
  * The token is minted lazily by `POST /api/session`, cached, and re-minted once on a 401 (Metabase expires sessions after `MAX_SESSION_AGE`, 14 days
  * by default, and a restart of Metabase with a fresh app DB invalidates it sooner).
  */
class MetabaseClient(config: MetabaseConfig, backend: SttpBackend[IO, Any], tokenRef: Ref[IO, Option[String]]) {

  private val base: Uri = uri"${config.baseUrl}"

  /** The current session token, logging in if we don't have one. */
  def sessionToken: IO[Either[String, String]] =
    tokenRef.get.flatMap {
      case Some(t) => IO.pure(Right(t))
      case None    => login()
    }

  private def login(): IO[Either[String, String]] = {
    val body = Json.obj("username" -> config.user.asJson, "password" -> config.password.asJson)
    basicRequest
      .post(base.addPath("api", "session"))
      .contentType("application/json")
      .body(body.noSpaces)
      .send(backend)
      .attempt
      .flatMap {
        case Left(e)     => IO.pure(Left(s"Metabase login failed: ${e.getMessage}"))
        case Right(resp) =>
          resp.body match {
            case Right(s) =>
              val token = parse(s).toOption.flatMap(_.hcursor.get[String]("id").toOption)
              token match {
                case Some(t) => tokenRef.set(Some(t)).as(Right(t))
                case None    => IO.pure(Left(s"Metabase login returned no session id: $s"))
              }
            case Left(e)  => IO.pure(Left(s"Metabase login failed (${resp.code}): $e"))
          }
      }
  }

  def get(segments: Seq[String]): IO[Either[String, Json]] = call("GET", segments, None)

  def post(segments: Seq[String], body: Json): IO[Either[String, Json]] = call("POST", segments, Some(body))

  def put(segments: Seq[String], body: Json): IO[Either[String, Json]] = call("PUT", segments, Some(body))

  def delete(segments: Seq[String]): IO[Either[String, Json]] = call("DELETE", segments, None)

  /** Unauthenticated GET — for `/api/health` and `/api/session/properties`, which must work before any user exists. */
  def getPublic(segments: Seq[String]): IO[Either[String, Json]] = send("GET", segments, None, None).map(_._2)

  /** Unauthenticated POST — only `/api/setup`, which is what creates the first user. */
  def postPublic(segments: Seq[String], body: Json): IO[Either[String, Json]] = send("POST", segments, Some(body), None).map(_._2)

  /** One request, with the session header attached when there is one. */
  private def send(method: String, segments: Seq[String], body: Option[Json], token: Option[String]): IO[(StatusCode, Either[String, Json])] = {
    val request  = basicRequest
      .method(Method(method), base.addPath(segments))
      .contentType("application/json")
    val withAuth = token.fold(request)(t => request.header("X-Metabase-Session", t))
    body.fold(withAuth)(b => withAuth.body(b.noSpaces)).send(backend).attempt.map {
      case Right(resp) =>
        val result = resp.body.left
          .map(e => s"Metabase $method /${segments.mkString("/")} failed (${resp.code}): $e")
          .flatMap(parseBody)
        (resp.code, result)
      // Metabase not answering is an ordinary outcome here, not a crash: at startup the app and
      // Metabase boot together and the first polls always hit a closed port. Raising would abort
      // the caller's retry loop, which is the one thing that must survive a booting Metabase.
      case Left(e)     =>
        (StatusCode.ServiceUnavailable, Left(s"Metabase $method /${segments.mkString("/")} unreachable: ${e.getMessage}"))
    }
  }

  /** Authenticated call, retrying once with a fresh token if Metabase says the session is gone. */
  private def call(method: String, segments: Seq[String], body: Option[Json]): IO[Either[String, Json]] =
    sessionToken.flatMap {
      case Left(e)  => IO.pure(Left(e))
      case Right(t) =>
        send(method, segments, body, Some(t)).flatMap {
          // Session expired or Metabase was reset — drop the cached token and try once more.
          case (StatusCode.Unauthorized, _) =>
            tokenRef.set(None) *> sessionToken.flatMap {
              case Left(e)   => IO.pure(Left(e))
              case Right(t2) => send(method, segments, body, Some(t2)).map(_._2)
            }
          case (_, result)                  => IO.pure(result)
        }
    }

  /** Metabase returns an empty body on some PUTs; treat that as `Json.Null` rather than a parse failure. */
  private def parseBody(s: String): Either[String, Json] =
    if s.trim.isEmpty then Right(Json.Null)
    else parse(s).left.map(e => s"Metabase returned unparseable JSON: ${e.getMessage}")
}

object MetabaseClient {
  def apply(config: MetabaseConfig, backend: SttpBackend[IO, Any]): IO[MetabaseClient] =
    Ref.of[IO, Option[String]](None).map(new MetabaseClient(config, backend, _))
}
