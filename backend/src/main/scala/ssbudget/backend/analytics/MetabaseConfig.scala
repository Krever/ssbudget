package ssbudget.backend.analytics

import cats.effect.IO

import java.nio.file.Paths

/** Configuration for the optional Metabase analytics instance.
  *
  * Analytics is additive: with `SSBUDGET_METABASE_URL` unset the app behaves exactly as it did before — no proxy route, no provisioning, and the
  * Analytics page reports itself unavailable.
  *
  *   - `baseUrl` — where Metabase listens, e.g. `http://127.0.0.1:3001`. Metabase should bind to loopback: the app's proxy is meant to be the only
  *     way in, so that the user's existing passkey session is the only credential involved.
  *   - `user` / `password` — a single Metabase service account. The backend logs in as it and injects the resulting session token; the browser never
  *     sees a Metabase credential.
  *   - `embeddingSecret` — must match Metabase's own `MB_EMBEDDING_SECRET_KEY`, since both sides sign/verify the static-embed JWT with it.
  *   - `path` — the public path prefix the proxy serves Metabase under. Metabase must be started with `MB_SITE_URL` ending in this same prefix so it
  *     emits `<base href="<path>/">` and its relative assets resolve correctly.
  *   - `dataDbPath` — absolute path to the SQLite file, as Metabase will open it.
  */
final case class MetabaseConfig(
    baseUrl: String,
    user: String,
    password: String,
    embeddingSecret: String,
    path: String,
    dataDbPath: String,
)

object MetabaseConfig {

  private val DefaultUser = "service@ssbudget.local"
  private val DefaultPath = "/metabase"

  /** Reads config from the environment. `None` means analytics is disabled, which is the default. */
  def fromEnv(dbPath: String): IO[Option[MetabaseConfig]] = IO.blocking {
    for {
      baseUrl  <- env("SSBUDGET_METABASE_URL")
      password <- env("SSBUDGET_METABASE_PASSWORD")
      secret   <- env("SSBUDGET_METABASE_EMBED_SECRET")
    } yield MetabaseConfig(
      baseUrl = baseUrl.stripSuffix("/"),
      user = env("SSBUDGET_METABASE_USER").getOrElse(DefaultUser),
      password = password,
      embeddingSecret = secret,
      path = normalisePath(env("SSBUDGET_METABASE_PATH").getOrElse(DefaultPath)),
      dataDbPath = Paths.get(dbPath).toAbsolutePath.normalize.toString,
    )
  }

  /** Leading slash, no trailing slash — the shape the proxy matches on. */
  private def normalisePath(p: String): String = {
    val trimmed = p.trim.stripSuffix("/")
    if trimmed.startsWith("/") then trimmed else s"/$trimmed"
  }

  private def env(name: String): Option[String] = sys.env.get(name).map(_.trim).filter(_.nonEmpty)
}
