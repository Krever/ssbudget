package ssbudget.shared.api

import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.*
import sttp.tapir.json.circe.*
import ssbudget.shared.api.TapirSchemas.given

object AuthEndpoints {

  /** Secured endpoint type with optional session cookie. */
  type Secured[I, O] = Endpoint[Option[String], I, ApiError, O, Any]

  /** Public endpoint type (no security input). */
  type Public[I, O] = Endpoint[Unit, I, ApiError, O, Any]

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
      .errorOut(ApiError.output)

  // Initial password setup (only works when not configured)
  // Returns session cookie on success (auto-login)
  val setup: Public[SetupRequest, CookieValueWithMeta] =
    baseEndpoint.post
      .in("setup")
      .in(jsonBody[SetupRequest])
      .out(setCookie(SessionCookieName))
      .errorOut(ApiError.output)

  // Password login - returns session cookie
  val login: Public[LoginRequest, CookieValueWithMeta] =
    baseEndpoint.post
      .in("login")
      .in(jsonBody[LoginRequest])
      .out(setCookie(SessionCookieName))
      .errorOut(ApiError.output)

  // Logout - clears session cookie (takes current session for invalidation)
  val logout: Secured[Unit, CookieValueWithMeta] =
    baseEndpoint.post
      .securityIn(sessionCookie)
      .in("logout")
      .out(setCookie(SessionCookieName))
      .errorOut(ApiError.output)

  // Challenge for an IdentityProof.Passkey confirming a sensitive action (authenticated; requires user verification)
  val verifyPasskeyStart: Secured[Unit, PasskeyAuthenticationOptions] =
    baseEndpoint.post
      .securityIn(sessionCookie)
      .in("passkey" / "verify" / "start")
      .out(jsonBody[PasskeyAuthenticationOptions])
      .errorOut(ApiError.output)

  // Proves the identity of the owner again in this session (authenticated, plus an IdentityProof). The proof is good for a short time, for the
  // actions that need more than the session: database export and import, and adding or deleting a passkey.
  val confirmIdentity: Secured[IdentityProof, Unit] =
    baseEndpoint.post
      .securityIn(sessionCookie)
      .in("confirm")
      .in(jsonBody[IdentityProof])
      .errorOut(ApiError.output)

  // Set or replace the password (authenticated, plus an IdentityProof)
  val changePassword: Secured[ChangePasswordRequest, Unit] =
    baseEndpoint.post
      .securityIn(sessionCookie)
      .in("password")
      .in(jsonBody[ChangePasswordRequest])
      .errorOut(ApiError.output)

  // Passkey endpoints

  // Start passkey registration (authenticated)
  val registerPasskeyStart: Secured[PasskeyRegisterStartRequest, PasskeyRegistrationOptions] =
    baseEndpoint.post
      .securityIn(sessionCookie)
      .in("passkey" / "register" / "start")
      .in(jsonBody[PasskeyRegisterStartRequest])
      .out(jsonBody[PasskeyRegistrationOptions])
      .errorOut(ApiError.output)

  // Finish passkey registration (authenticated)
  val registerPasskeyFinish: Secured[PasskeyRegistrationResponse, Unit] =
    baseEndpoint.post
      .securityIn(sessionCookie)
      .in("passkey" / "register" / "finish")
      .in(jsonBody[PasskeyRegistrationResponse])
      .errorOut(ApiError.output)

  // Start passkey authentication (public)
  val loginPasskeyStart: Public[Unit, PasskeyAuthenticationOptions] =
    baseEndpoint.post
      .in("passkey" / "login" / "start")
      .out(jsonBody[PasskeyAuthenticationOptions])
      .errorOut(ApiError.output)

  // Finish passkey authentication - returns session cookie
  val loginPasskeyFinish: Public[PasskeyAuthenticationResponse, CookieValueWithMeta] =
    baseEndpoint.post
      .in("passkey" / "login" / "finish")
      .in(jsonBody[PasskeyAuthenticationResponse])
      .out(setCookie(SessionCookieName))
      .errorOut(ApiError.output)

  // List registered passkeys (authenticated)
  val listPasskeys: Secured[Unit, List[PasskeyInfo]] =
    baseEndpoint.get
      .securityIn(sessionCookie)
      .in("passkeys")
      .out(jsonBody[List[PasskeyInfo]])
      .errorOut(ApiError.output)

  // Delete a passkey (authenticated)
  val deletePasskey: Secured[String, Unit] =
    baseEndpoint.delete
      .securityIn(sessionCookie)
      .in("passkeys" / path[String]("credentialId"))
      .errorOut(ApiError.output)

  // MCP access tokens (for the /mcp endpoint; managed on the Settings page)

  val listMcpTokens: Secured[Unit, List[McpTokenInfo]] =
    baseEndpoint.get
      .securityIn(sessionCookie)
      .in("mcp-tokens")
      .out(jsonBody[List[McpTokenInfo]])
      .errorOut(ApiError.output)

  // Authenticated, plus an IdentityProof. The answer is the only place where the token itself appears
  val createMcpToken: Secured[CreateMcpTokenRequest, CreatedMcpToken] =
    baseEndpoint.post
      .securityIn(sessionCookie)
      .in("mcp-tokens")
      .in(jsonBody[CreateMcpTokenRequest])
      .out(jsonBody[CreatedMcpToken])
      .errorOut(ApiError.output)

  val revokeMcpToken: Secured[String, Unit] =
    baseEndpoint.delete
      .securityIn(sessionCookie)
      .in("mcp-tokens" / path[String]("id"))
      .errorOut(ApiError.output)

  val all: List[AnyEndpoint] = List(
    status,
    setup,
    login,
    logout,
    verifyPasskeyStart,
    confirmIdentity,
    changePassword,
    registerPasskeyStart,
    registerPasskeyFinish,
    loginPasskeyStart,
    loginPasskeyFinish,
    listPasskeys,
    deletePasskey,
    listMcpTokens,
    createMcpToken,
    revokeMcpToken,
  )

  /** Client-side endpoint definitions for browser use.
    *
    * These differ from the server endpoints in two ways: 1. Endpoints that return cookies return Unit instead of CookieValueWithMeta because browsers
    * handle Set-Cookie headers automatically, and the header is not accessible to JavaScript for security reasons. 2. Endpoints that require
    * authentication don't have securityIn because the browser automatically sends cookies with credentials:include.
    */
  object client {
    val status: Public[Unit, AuthStatus] =
      baseEndpoint.get.in("status").out(jsonBody[AuthStatus]).errorOut(ApiError.output)

    val setup: Public[SetupRequest, Unit] =
      baseEndpoint.post.in("setup").in(jsonBody[SetupRequest]).errorOut(ApiError.output)

    val login: Public[LoginRequest, Unit] =
      baseEndpoint.post.in("login").in(jsonBody[LoginRequest]).errorOut(ApiError.output)

    val logout: Public[Unit, Unit] =
      baseEndpoint.post.in("logout").errorOut(ApiError.output)

    val verifyPasskeyStart: Public[Unit, PasskeyAuthenticationOptions] =
      baseEndpoint.post.in("passkey" / "verify" / "start").out(jsonBody[PasskeyAuthenticationOptions]).errorOut(ApiError.output)

    val confirmIdentity: Public[IdentityProof, Unit] =
      baseEndpoint.post.in("confirm").in(jsonBody[IdentityProof]).errorOut(ApiError.output)

    val changePassword: Public[ChangePasswordRequest, Unit] =
      baseEndpoint.post.in("password").in(jsonBody[ChangePasswordRequest]).errorOut(ApiError.output)

    val loginPasskeyStart: Public[Unit, PasskeyAuthenticationOptions] =
      baseEndpoint.post.in("passkey" / "login" / "start").out(jsonBody[PasskeyAuthenticationOptions]).errorOut(ApiError.output)

    val loginPasskeyFinish: Public[PasskeyAuthenticationResponse, Unit] =
      baseEndpoint.post.in("passkey" / "login" / "finish").in(jsonBody[PasskeyAuthenticationResponse]).errorOut(ApiError.output)

    val listPasskeys: Public[Unit, List[PasskeyInfo]] =
      baseEndpoint.get.in("passkeys").out(jsonBody[List[PasskeyInfo]]).errorOut(ApiError.output)

    val deletePasskey: Public[String, Unit] =
      baseEndpoint.delete.in("passkeys" / path[String]("credentialId")).errorOut(ApiError.output)

    val listMcpTokens: Public[Unit, List[McpTokenInfo]] =
      baseEndpoint.get.in("mcp-tokens").out(jsonBody[List[McpTokenInfo]]).errorOut(ApiError.output)

    val createMcpToken: Public[CreateMcpTokenRequest, CreatedMcpToken] =
      baseEndpoint.post.in("mcp-tokens").in(jsonBody[CreateMcpTokenRequest]).out(jsonBody[CreatedMcpToken]).errorOut(ApiError.output)

    val revokeMcpToken: Public[String, Unit] =
      baseEndpoint.delete.in("mcp-tokens" / path[String]("id")).errorOut(ApiError.output)

    val registerPasskeyStart: Public[PasskeyRegisterStartRequest, PasskeyRegistrationOptions] =
      baseEndpoint.post
        .in("passkey" / "register" / "start")
        .in(jsonBody[PasskeyRegisterStartRequest])
        .out(jsonBody[PasskeyRegistrationOptions])
        .errorOut(ApiError.output)

    val registerPasskeyFinish: Public[PasskeyRegistrationResponse, Unit] =
      baseEndpoint.post.in("passkey" / "register" / "finish").in(jsonBody[PasskeyRegistrationResponse]).errorOut(ApiError.output)
  }
}
