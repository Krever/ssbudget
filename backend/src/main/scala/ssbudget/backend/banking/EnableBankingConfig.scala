package ssbudget.backend.banking

import cats.effect.IO

import java.nio.file.{Files, Paths}

/** Enable Banking integration config.
  *
  * The RSA private key is the crown jewel — it is read from a fly.io secret / env var and NEVER stored in the database (which is exportable via the
  * import/export endpoints). Provide the key either inline (`EB_PRIVATE_KEY`, e.g. `fly secrets set EB_PRIVATE_KEY="$(cat <app-id>.pem)"`) or as a
  * file path (`EB_PRIVATE_KEY_PATH`, convenient for local dev pointing at the gitignored .pem).
  */
final case class EnableBankingConfig(
    appId: String,
    privateKeyPem: String,
    baseUrl: String,
    redirectUrl: String,
)

object EnableBankingConfig {

  /** Reads config from the environment. Returns None when the integration is not configured, so the app still runs without Enable Banking. */
  def fromEnv: IO[Option[EnableBankingConfig]] = IO.blocking {
    for {
      appId <- sys.env.get("EB_APP_ID").map(_.trim).filter(_.nonEmpty)
      pem   <- readPrivateKey
    } yield EnableBankingConfig(
      appId = appId,
      privateKeyPem = pem,
      baseUrl = sys.env.get("EB_BASE_URL").map(_.trim).filter(_.nonEmpty).getOrElse("https://api.enablebanking.com"),
      redirectUrl = sys.env.get("EB_REDIRECT_URL").map(_.trim).filter(_.nonEmpty).getOrElse("https://localhost:3000/banking/callback"),
    )
  }

  private def readPrivateKey: Option[String] =
    sys.env
      .get("EB_PRIVATE_KEY_PATH")
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(p => new String(Files.readAllBytes(Paths.get(p)), "UTF-8"))
      .orElse(sys.env.get("EB_PRIVATE_KEY").map(_.trim).filter(_.nonEmpty))
}
