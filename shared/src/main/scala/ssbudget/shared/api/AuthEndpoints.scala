package ssbudget.shared.api

import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.*
import sttp.tapir.json.circe.*
import ssbudget.shared.api.TapirSchemas.given

object AuthEndpoints {

  /** Secured endpoint type with optional session cookie. */
  type Secured[I, O] = Endpoint[Option[String], I, String, O, Any]

  /** Public endpoint type (no security input). */
  type Public[I, O] = Endpoint[Unit, I, String, O, Any]

  val SessionCookieName = "ssbudget_session"

  private val baseEndpoint = endpoint.in("api" / "auth")

  // Security input for reading optional session cookie
  // Using optional to allow decode to succeed even when cookie is absent,
  // so serverSecurityLogic can handle missing cookies (e.g., bypass in testMode)
  val sessionCookie: EndpointInput.Auth[Option[String], EndpointInput.AuthType.ApiKey] =
    auth.apiKey(cookie[Option[String]](SessionCookieName))

  // Get current auth status - needs optional session to check if logged in
  val status: Secured[Unit, AuthStatus] =
    baseEndpoint.get
      .securityIn(sessionCookie)
      .in("status")
      .out(jsonBody[AuthStatus])
      .errorOut(stringBody)

  // Initial password setup (only works when not configured)
  // Returns session cookie on success (auto-login)
  val setup: Public[SetupRequest, CookieValueWithMeta] =
    baseEndpoint.post
      .in("setup")
      .in(jsonBody[SetupRequest])
      .out(setCookie(SessionCookieName))
      .errorOut(stringBody)

  // Password login - returns session cookie
  val login: Public[LoginRequest, CookieValueWithMeta] =
    baseEndpoint.post
      .in("login")
      .in(jsonBody[LoginRequest])
      .out(setCookie(SessionCookieName))
      .errorOut(stringBody)

  // Logout - clears session cookie (takes current session for invalidation)
  val logout: Secured[Unit, CookieValueWithMeta] =
    baseEndpoint.post
      .securityIn(sessionCookie)
      .in("logout")
      .out(setCookie(SessionCookieName))
      .errorOut(stringBody)

  // Passkey endpoints

  // Start passkey registration (authenticated)
  val registerPasskeyStart: Secured[PasskeyRegisterStartRequest, PasskeyRegistrationOptions] =
    baseEndpoint.post
      .securityIn(sessionCookie)
      .in("passkey" / "register" / "start")
      .in(jsonBody[PasskeyRegisterStartRequest])
      .out(jsonBody[PasskeyRegistrationOptions])
      .errorOut(stringBody)

  // Finish passkey registration (authenticated)
  val registerPasskeyFinish: Secured[PasskeyRegistrationResponse, Unit] =
    baseEndpoint.post
      .securityIn(sessionCookie)
      .in("passkey" / "register" / "finish")
      .in(jsonBody[PasskeyRegistrationResponse])
      .errorOut(stringBody)

  // Start passkey authentication (public)
  val loginPasskeyStart: Public[Unit, PasskeyAuthenticationOptions] =
    baseEndpoint.post
      .in("passkey" / "login" / "start")
      .out(jsonBody[PasskeyAuthenticationOptions])
      .errorOut(stringBody)

  // Finish passkey authentication - returns session cookie
  val loginPasskeyFinish: Public[PasskeyAuthenticationResponse, CookieValueWithMeta] =
    baseEndpoint.post
      .in("passkey" / "login" / "finish")
      .in(jsonBody[PasskeyAuthenticationResponse])
      .out(setCookie(SessionCookieName))
      .errorOut(stringBody)

  // List registered passkeys (authenticated)
  val listPasskeys: Secured[Unit, List[PasskeyInfo]] =
    baseEndpoint.get
      .securityIn(sessionCookie)
      .in("passkeys")
      .out(jsonBody[List[PasskeyInfo]])
      .errorOut(stringBody)

  // Delete a passkey (authenticated)
  val deletePasskey: Secured[String, Unit] =
    baseEndpoint.delete
      .securityIn(sessionCookie)
      .in("passkeys" / path[String]("credentialId"))
      .errorOut(stringBody)

  val all: List[AnyEndpoint] = List(
    status,
    setup,
    login,
    logout,
    registerPasskeyStart,
    registerPasskeyFinish,
    loginPasskeyStart,
    loginPasskeyFinish,
    listPasskeys,
    deletePasskey,
  )

  /** Client-side endpoint definitions for browser use.
    *
    * These differ from the server endpoints in two ways: 1. Endpoints that return cookies return Unit instead of CookieValueWithMeta because browsers
    * handle Set-Cookie headers automatically, and the header is not accessible to JavaScript for security reasons. 2. Endpoints that require
    * authentication don't have securityIn because the browser automatically sends cookies with credentials:include.
    */
  object client {
    val status: Public[Unit, AuthStatus] =
      baseEndpoint.get.in("status").out(jsonBody[AuthStatus]).errorOut(stringBody)

    val setup: Public[SetupRequest, Unit] =
      baseEndpoint.post.in("setup").in(jsonBody[SetupRequest]).errorOut(stringBody)

    val login: Public[LoginRequest, Unit] =
      baseEndpoint.post.in("login").in(jsonBody[LoginRequest]).errorOut(stringBody)

    val logout: Public[Unit, Unit] =
      baseEndpoint.post.in("logout").errorOut(stringBody)

    val loginPasskeyStart: Public[Unit, PasskeyAuthenticationOptions] =
      baseEndpoint.post.in("passkey" / "login" / "start").out(jsonBody[PasskeyAuthenticationOptions]).errorOut(stringBody)

    val loginPasskeyFinish: Public[PasskeyAuthenticationResponse, Unit] =
      baseEndpoint.post.in("passkey" / "login" / "finish").in(jsonBody[PasskeyAuthenticationResponse]).errorOut(stringBody)

    val listPasskeys: Public[Unit, List[PasskeyInfo]] =
      baseEndpoint.get.in("passkeys").out(jsonBody[List[PasskeyInfo]]).errorOut(stringBody)

    val deletePasskey: Public[String, Unit] =
      baseEndpoint.delete.in("passkeys" / path[String]("credentialId")).errorOut(stringBody)

    val registerPasskeyStart: Public[PasskeyRegisterStartRequest, PasskeyRegistrationOptions] =
      baseEndpoint.post
        .in("passkey" / "register" / "start")
        .in(jsonBody[PasskeyRegisterStartRequest])
        .out(jsonBody[PasskeyRegistrationOptions])
        .errorOut(stringBody)

    val registerPasskeyFinish: Public[PasskeyRegistrationResponse, Unit] =
      baseEndpoint.post.in("passkey" / "register" / "finish").in(jsonBody[PasskeyRegistrationResponse]).errorOut(stringBody)
  }
}
