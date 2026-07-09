package ssbudget.backend.banking

import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.syntax.*

import java.security.spec.PKCS8EncodedKeySpec
import java.security.{KeyFactory, PrivateKey, Signature}
import java.time.Instant
import java.util.Base64

/** Mints the RS256 JWT that authenticates the application against Enable Banking.
  *
  * Header: `{ typ, alg: RS256, kid: <app_id> }`. Payload: `{ iss: enablebanking.com, aud: api.enablebanking.com, iat, exp }`. Max validity is 24h; we
  * mint 1h tokens and cache them in-memory, refreshing shortly before expiry.
  */
class EnableBankingJwt private (config: EnableBankingConfig, privateKey: PrivateKey, cache: Ref[IO, Option[(String, Instant)]]) {

  def token: IO[String] =
    for {
      now    <- IO.realTimeInstant
      cached <- cache.get
      result <- cached match {
                  case Some((tok, exp)) if exp.isAfter(now.plusSeconds(300)) => IO.pure(tok)
                  case _                                                     =>
                    val exp = now.plusSeconds(3600)
                    IO(mint(now, exp)).flatTap(tok => cache.set(Some((tok, exp))))
                }
    } yield result

  private def mint(iat: Instant, exp: Instant): String = {
    val header       = Json.obj(
      "typ" -> "JWT".asJson,
      "alg" -> "RS256".asJson,
      "kid" -> config.appId.asJson,
    )
    val payload      = Json.obj(
      "iss" -> "enablebanking.com".asJson,
      "aud" -> "api.enablebanking.com".asJson,
      "iat" -> iat.getEpochSecond.asJson,
      "exp" -> exp.getEpochSecond.asJson,
    )
    val enc          = Base64.getUrlEncoder.withoutPadding()
    val headerPart   = enc.encodeToString(header.noSpaces.getBytes("UTF-8"))
    val payloadPart  = enc.encodeToString(payload.noSpaces.getBytes("UTF-8"))
    val signingInput = s"$headerPart.$payloadPart"

    val signer    = Signature.getInstance("SHA256withRSA")
    signer.initSign(privateKey)
    signer.update(signingInput.getBytes("UTF-8"))
    val signature = enc.encodeToString(signer.sign())

    s"$signingInput.$signature"
  }
}

object EnableBankingJwt {

  def create(config: EnableBankingConfig): IO[EnableBankingJwt] =
    for {
      key   <- IO(parsePrivateKey(config.privateKeyPem))
      cache <- Ref.of[IO, Option[(String, Instant)]](None)
    } yield new EnableBankingJwt(config, key, cache)

  /** Parses a PKCS#8 PEM private key (`-----BEGIN PRIVATE KEY-----`), which is what the Enable Banking control panel generates. */
  private def parsePrivateKey(pem: String): PrivateKey = {
    val base64 = pem
      .replaceAll("-----BEGIN (RSA )?PRIVATE KEY-----", "")
      .replaceAll("-----END (RSA )?PRIVATE KEY-----", "")
      .replaceAll("\\s", "")
    val der    = Base64.getDecoder.decode(base64)
    KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der))
  }
}
