package ssbudget.backend.auth

import cats.effect.IO

import java.security.SecureRandom
import java.util.Base64

/** Makes the random secrets of the app (session tokens, MCP access tokens): bytes from a `SecureRandom`, as URL-safe base64 without padding. */
object SecureToken {

  private val random = new SecureRandom()

  def generate(bytes: Int): IO[String] = IO.blocking {
    val buffer = new Array[Byte](bytes)
    random.nextBytes(buffer)
    Base64.getUrlEncoder.withoutPadding().encodeToString(buffer)
  }
}
