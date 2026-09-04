package ssbudget.backend.analytics

import io.circe.Json
import io.circe.syntax.*

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Mints the signed JWT for Metabase static embedding.
  *
  * Static embedding is the one Metabase auth path that involves no user and no session: the token names a single resource, locks its parameters, and
  * carries an expiry. Metabase verifies the HS256 signature against `MB_EMBEDDING_SECRET_KEY` and renders that resource, so the URL *is* the
  * credential — hence a short expiry and a fresh token per page load.
  *
  * HS256 over `base64url(header).base64url(payload)`, which `javax.crypto` covers, so this needs no JWT dependency.
  */
object EmbedToken {

  /** How long a minted embed URL stays valid. Long enough to load and interact with a dashboard, short enough that a leaked URL is worthless. */
  private val Validity = java.time.Duration.ofMinutes(10)

  def forDashboard(secret: String, dashboardId: Int, now: Instant): String = {
    val header       = Json.obj("alg" -> "HS256".asJson, "typ" -> "JWT".asJson)
    val payload      = Json.obj(
      "resource" -> Json.obj("dashboard" -> dashboardId.asJson),
      "params"   -> Json.obj(),
      "exp"      -> now.plus(Validity).getEpochSecond.asJson,
    )
    val signingInput = s"${b64(header.noSpaces)}.${b64(payload.noSpaces)}"
    s"$signingInput.${b64url(hmacSha256(secret, signingInput))}"
  }

  private def hmacSha256(secret: String, data: String): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    mac.doFinal(data.getBytes(UTF_8))
  }

  private def b64(s: String): String = b64url(s.getBytes(UTF_8))

  private def b64url(bytes: Array[Byte]): String = Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
}
