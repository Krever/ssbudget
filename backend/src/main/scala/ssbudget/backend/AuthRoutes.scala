package ssbudget.backend

import cats.effect.IO
import cats.implicits.*
import org.http4s.HttpRoutes
import sttp.model.headers.CookieValueWithMeta
import sttp.tapir.server.http4s.Http4sServerInterpreter
import ssbudget.backend.auth.{PasswordService, SessionService, WebAuthnService}
import ssbudget.backend.db.repository.{AuthConfigRepository, PasskeyCredentialRepository}
import ssbudget.shared.api.*

import java.time.Duration as JDuration

object AuthRoutes {

  // Cookie configuration
  private val cookieMaxAge = JDuration.ofDays(30).toSeconds
  private val cookieSecure = sys.env.get("SSBUDGET_COOKIE_SECURE").exists(_.toLowerCase == "true")

  private def sessionCookie(token: String): CookieValueWithMeta =
    CookieValueWithMeta.unsafeApply(
      value = token,
      expires = None,
      maxAge = Some(cookieMaxAge),
      domain = None,
      path = Some("/"),
      secure = cookieSecure,
      httpOnly = true,
      sameSite = None,
      otherDirectives = Map.empty,
    )

  private def clearCookie: CookieValueWithMeta =
    CookieValueWithMeta.unsafeApply(
      value = "",
      expires = None,
      maxAge = Some(0),
      domain = None,
      path = Some("/"),
      secure = cookieSecure,
      httpOnly = true,
      sameSite = None,
      otherDirectives = Map.empty,
    )

  def make(
      authConfigRepo: AuthConfigRepository,
      passkeyRepo: PasskeyCredentialRepository,
      passwordService: PasswordService,
      sessionService: SessionService,
      webAuthnService: WebAuthnService,
      testMode: Boolean = false,
  ): HttpRoutes[IO] = {
    val interpreter = Http4sServerInterpreter[IO]()

    // Status endpoint - uses optional session cookie to check login state
    val statusRoute = interpreter.toRoutes(
      AuthEndpoints.status
        .serverSecurityLogic(tokenOpt => checkLoginStatus(sessionService, tokenOpt, testMode))
        .serverLogic(loggedIn => _ => getAuthStatus(authConfigRepo, passkeyRepo, loggedIn, testMode)),
    )

    // Setup endpoint - creates password and returns session cookie
    val setupRoute = interpreter.toRoutes(
      AuthEndpoints.setup.serverLogic(req => setupPassword(authConfigRepo, passwordService, sessionService, req)),
    )

    // Login endpoint - validates password and returns session cookie
    val loginRoute = interpreter.toRoutes(
      AuthEndpoints.login.serverLogic(req => login(authConfigRepo, passwordService, sessionService, req)),
    )

    // Logout endpoint - invalidates session and clears cookie
    val logoutRoute = interpreter.toRoutes(
      AuthEndpoints.logout
        .serverSecurityLogic(tokenOpt => IO.pure(Right(tokenOpt)))
        .serverLogic(tokenOpt => _ => logout(sessionService, tokenOpt)),
    )

    // Passkey registration start (authenticated)
    val registerPasskeyStartRoute = interpreter.toRoutes(
      AuthEndpoints.registerPasskeyStart
        .serverSecurityLogic(token => validateSession(sessionService, token, testMode))
        .serverLogic(_ => req => startPasskeyRegistration(webAuthnService, req)),
    )

    // Passkey registration finish (authenticated)
    val registerPasskeyFinishRoute = interpreter.toRoutes(
      AuthEndpoints.registerPasskeyFinish
        .serverSecurityLogic(token => validateSession(sessionService, token, testMode))
        .serverLogic(_ => req => finishPasskeyRegistration(webAuthnService, req)),
    )

    // Passkey login start (public)
    val loginPasskeyStartRoute = interpreter.toRoutes(
      AuthEndpoints.loginPasskeyStart.serverLogic(_ => startPasskeyLogin(webAuthnService)),
    )

    // Passkey login finish - validates and returns session cookie
    val loginPasskeyFinishRoute = interpreter.toRoutes(
      AuthEndpoints.loginPasskeyFinish.serverLogic(req => finishPasskeyLogin(webAuthnService, sessionService, req)),
    )

    // List passkeys (authenticated)
    val listPasskeysRoute = interpreter.toRoutes(
      AuthEndpoints.listPasskeys
        .serverSecurityLogic(token => validateSession(sessionService, token, testMode))
        .serverLogic(_ => _ => listPasskeys(passkeyRepo)),
    )

    // Delete passkey (authenticated)
    val deletePasskeyRoute = interpreter.toRoutes(
      AuthEndpoints.deletePasskey
        .serverSecurityLogic(token => validateSession(sessionService, token, testMode))
        .serverLogic(_ => credId => deletePasskey(passkeyRepo, credId)),
    )

    statusRoute <+>
      setupRoute <+>
      loginRoute <+>
      logoutRoute <+>
      registerPasskeyStartRoute <+>
      registerPasskeyFinishRoute <+>
      loginPasskeyStartRoute <+>
      loginPasskeyFinishRoute <+>
      listPasskeysRoute <+>
      deletePasskeyRoute
  }

  /** Validates a session token and returns Unit if valid. Used by protected endpoints.
    *
    * In testMode, bypasses authentication entirely. Otherwise, requires a valid session token.
    */
  def validateSession(sessionService: SessionService, tokenOpt: Option[String], testMode: Boolean): IO[Either[String, Unit]] = {
    if testMode then {
      IO.pure(Right(()))
    } else {
      tokenOpt match {
        case Some(token) =>
          sessionService.validateSession(token).map {
            case Some(_) => Right(())
            case None    => Left("Unauthorized")
          }
        case None        => IO.pure(Left("Unauthorized"))
      }
    }
  }

  /** Checks if session token is valid, returns boolean for status endpoint.
    *
    * In testMode, returns true to bypass authentication entirely.
    */
  private def checkLoginStatus(
      sessionService: SessionService,
      tokenOpt: Option[String],
      testMode: Boolean,
  ): IO[Either[String, Boolean]] = {
    if testMode then {
      // In test mode, always return logged in
      IO.pure(Right(true))
    } else {
      tokenOpt match {
        case Some(token) =>
          sessionService.validateSession(token).map {
            case Some(_) => Right(true)
            case None    => Right(false)
          }
        case None        => IO.pure(Right(false))
      }
    }
  }

  private def getAuthStatus(
      authConfigRepo: AuthConfigRepository,
      passkeyRepo: PasskeyCredentialRepository,
      loggedIn: Boolean,
      testMode: Boolean,
  ): IO[Either[String, AuthStatus]] = {
    // In test mode, return configured=true and loggedIn=true to bypass auth UI
    if testMode then {
      IO.pure(Right(AuthStatus(configured = true, passkeyCount = 0, loggedIn = true)))
    } else {
      for {
        configOpt    <- authConfigRepo.get
        passkeyCount <- passkeyRepo.count
        configured    = configOpt.exists(_.passwordHash.isDefined) || passkeyCount > 0
      } yield Right(AuthStatus(configured, passkeyCount, loggedIn))
    }
  }

  private def setupPassword(
      authConfigRepo: AuthConfigRepository,
      passwordService: PasswordService,
      sessionService: SessionService,
      req: SetupRequest,
  ): IO[Either[String, CookieValueWithMeta]] = {
    for {
      configOpt <- authConfigRepo.get
      result    <- configOpt match {
                     case Some(config) if config.passwordHash.isDefined =>
                       IO.pure(Left("Password already configured"))
                     case _                                             =>
                       for {
                         hash    <- passwordService.hash(req.password)
                         _       <- authConfigRepo.upsert(hash)
                         session <- sessionService.createSession()
                       } yield Right(sessionCookie(session.token))
                   }
    } yield result
  }

  private def login(
      authConfigRepo: AuthConfigRepository,
      passwordService: PasswordService,
      sessionService: SessionService,
      req: LoginRequest,
  ): IO[Either[String, CookieValueWithMeta]] = {
    for {
      configOpt <- authConfigRepo.get
      result    <- configOpt match {
                     case Some(config) if config.passwordHash.isDefined =>
                       for {
                         valid  <- passwordService.verify(req.password, config.passwordHash.get)
                         result <- if valid then {
                                     sessionService.createSession().map(s => Right(sessionCookie(s.token)))
                                   } else {
                                     IO.pure(Left("Invalid password"))
                                   }
                       } yield result
                     case _                                             =>
                       IO.pure(Left("Authentication not configured"))
                   }
    } yield result
  }

  private def logout(sessionService: SessionService, tokenOpt: Option[String]): IO[Either[String, CookieValueWithMeta]] = {
    for {
      _ <- tokenOpt.fold(IO.unit)(token => sessionService.invalidateSession(token))
    } yield Right(clearCookie)
  }

  private def listPasskeys(passkeyRepo: PasskeyCredentialRepository): IO[Either[String, List[PasskeyInfo]]] = {
    passkeyRepo.findAll.map { credentials =>
      Right(
        credentials.map { cred =>
          PasskeyInfo(cred.credentialId, cred.displayName, cred.createdAt, cred.lastUsedAt)
        },
      )
    }
  }

  private def deletePasskey(passkeyRepo: PasskeyCredentialRepository, credentialId: String): IO[Either[String, Unit]] = {
    passkeyRepo.delete(credentialId).map(_ => Right(()))
  }

  private def startPasskeyRegistration(
      webAuthnService: WebAuthnService,
      req: PasskeyRegisterStartRequest,
  ): IO[Either[String, PasskeyRegistrationOptions]] = {
    webAuthnService.startRegistration(req.displayName).map(Right(_)).handleError(e => Left(e.getMessage))
  }

  private def finishPasskeyRegistration(
      webAuthnService: WebAuthnService,
      req: PasskeyRegistrationResponse,
  ): IO[Either[String, Unit]] = {
    webAuthnService.finishRegistration(req).map(_ => Right(())).handleError(e => Left(e.getMessage))
  }

  private def startPasskeyLogin(webAuthnService: WebAuthnService): IO[Either[String, PasskeyAuthenticationOptions]] = {
    webAuthnService.startAuthentication().map(Right(_)).handleError(e => Left(e.getMessage))
  }

  private def finishPasskeyLogin(
      webAuthnService: WebAuthnService,
      sessionService: SessionService,
      req: PasskeyAuthenticationResponse,
  ): IO[Either[String, CookieValueWithMeta]] = {
    webAuthnService.finishAuthentication(req).attempt.flatMap {
      case Right(_)    => sessionService.createSession().map(s => Right(sessionCookie(s.token)))
      case Left(error) => IO.pure(Left(error.getMessage))
    }
  }
}
