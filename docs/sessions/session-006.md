# Session 006: Authentication (Password + Passkeys)

**Date**: 2026-01-28
**Phase**: 5 (Authentication)
**Items Completed**: 5.1, 5.2, 5.3, 5.4, 5.5

## Summary

Implemented full authentication system with both password and WebAuthn passkey support. First-time visitors set up a password, then can optionally add passkeys for passwordless login. All data endpoints are protected with session-based authentication.

## Changes Made

### Backend Auth Services

#### WebAuthnService (`backend/src/main/scala/ssbudget/backend/auth/WebAuthnService.scala`)
- WebAuthn RelyingParty configuration (rpId, rpName, rpOrigins from env vars)
- Registration flow: startRegistration, finishRegistration
- Authentication flow: startAuthentication, finishAuthentication
- Thread-safe pending challenge storage using cats.effect.Ref
- Credential storage in SQLite

#### SessionService (`backend/src/main/scala/ssbudget/backend/auth/SessionService.scala`)
- 30-day session tokens with secure random generation
- Session validation, creation, invalidation
- Expired session cleanup

#### PasswordService (`backend/src/main/scala/ssbudget/backend/auth/PasswordService.scala`)
- Argon2id password hashing
- Password verification

#### AuthRoutes (`backend/src/main/scala/ssbudget/backend/AuthRoutes.scala`)
- Status endpoint (check if configured, logged in, passkey count)
- Setup endpoint (initial password creation, auto-login)
- Login/logout endpoints (password-based)
- Passkey registration endpoints (authenticated)
- Passkey login endpoints (public)
- Passkey management (list, delete)
- HttpOnly session cookies with configurable secure flag

### Database Schema

#### V2__auth_schema.sql
- `auth_config` - singleton table for password hash
- `sessions` - session tokens with expiry
- `passkey_credentials` - WebAuthn credentials (credential_id, public_key_cose, sign_count)

### Frontend Auth

#### AuthState (`frontend/src/main/scala/ssbudget/frontend/auth/AuthState.scala`)
- Global auth state: Loading, NeedsSetup, NeedsLogin, LoggedIn, Error
- Initialize and refresh status from API
- Logout handling

#### WebAuthnFacade (`frontend/src/main/scala/ssbudget/frontend/util/WebAuthnFacade.scala`)
- Browser WebAuthn API wrapper for Scala.js
- createCredential for registration
- getCredential for authentication
- Base64URL encoding/decoding

#### Auth Pages
- `SetupPage.scala` - Initial password setup with confirmation
- `LoginPage.scala` - Password login with optional passkey button
- `SettingsPage.scala` - Passkey management (list, add, delete)

#### Main.scala Updates
- Auth state initialization before app render
- Conditional rendering based on auth state
- Protected routes only shown when logged in

### Shared API

#### AuthDto.scala
- AuthStatus, SetupRequest, LoginRequest
- PasskeyInfo, PasskeyRegistrationOptions, PasskeyAuthenticationOptions
- WebAuthn response types (AttestationResponse, AssertionResponse)

#### AuthEndpoints.scala
- Server endpoints with session cookie security
- Client endpoints without securityIn (browser sends cookies automatically)
- All auth endpoints under /api/auth/*

### Protected Routes

All data endpoints now require authentication:
- Routes.scala uses `serverSecurityLogic` with session validation
- Endpoints.scala defines `Secured[I, O]` type with session cookie input
- testMode flag bypasses auth for e2e tests

### E2E Auth Tests

#### AuthSpec.scala
- Show setup page on first visit
- Setup password and auto-login
- Logout and show login page
- Login with correct/wrong password
- Password mismatch validation on setup

#### AuthTestServers.scala
- Separate test infrastructure with auth ENABLED (testMode=false)
- Database reset between tests

## Technical Decisions

1. **Password + Passkeys**: Added password auth as baseline, passkeys as upgrade path. Users must set up password first, then can add passkeys.

2. **Session cookies**: HttpOnly cookies for security (not accessible to JS). Configurable secure flag via SSBUDGET_COOKIE_SECURE env var.

3. **Thread-safe WebAuthn state**: Pending challenges stored in Ref[IO, Option[...]] to prevent race conditions.

4. **testMode for existing e2e tests**: Data tests run with testMode=true to bypass auth. Auth tests run separately with testMode=false.

5. **Client vs Server endpoints**: Separate endpoint definitions because browsers handle Set-Cookie automatically and send cookies with requests.

## Files Created

### Backend
- `backend/src/main/scala/ssbudget/backend/AuthRoutes.scala`
- `backend/src/main/scala/ssbudget/backend/auth/WebAuthnService.scala`
- `backend/src/main/scala/ssbudget/backend/auth/SessionService.scala`
- `backend/src/main/scala/ssbudget/backend/auth/PasswordService.scala`
- `backend/src/main/scala/ssbudget/backend/db/repository/AuthConfigRepository.scala`
- `backend/src/main/scala/ssbudget/backend/db/repository/SessionRepository.scala`
- `backend/src/main/scala/ssbudget/backend/db/repository/PasskeyCredentialRepository.scala`
- `backend/src/main/resources/db/migration/V2__auth_schema.sql`

### Frontend
- `frontend/src/main/scala/ssbudget/frontend/auth/AuthState.scala`
- `frontend/src/main/scala/ssbudget/frontend/util/WebAuthnFacade.scala`
- `frontend/src/main/scala/ssbudget/frontend/pages/LoginPage.scala`
- `frontend/src/main/scala/ssbudget/frontend/pages/SetupPage.scala`
- `frontend/src/main/scala/ssbudget/frontend/pages/SettingsPage.scala`

### Shared
- `shared/src/main/scala/ssbudget/shared/api/AuthDto.scala`
- `shared/src/main/scala/ssbudget/shared/api/AuthEndpoints.scala`

### E2E
- `e2e/src/test/scala/ssbudget/e2e/AuthSpec.scala`
- `e2e/src/test/scala/ssbudget/e2e/AuthTestServers.scala`

## Files Modified

### Backend
- `backend/src/main/scala/ssbudget/backend/Routes.scala` - Added session validation
- `backend/src/main/scala/ssbudget/backend/ServerBuilder.scala` - WebAuthnService, auth routes
- `backend/src/main/scala/ssbudget/backend/db/Repositories.scala` - Auth repositories

### Frontend
- `frontend/src/main/scala/ssbudget/frontend/Main.scala` - Auth state handling
- `frontend/src/main/scala/ssbudget/frontend/Page.scala` - Added Settings page
- `frontend/src/main/scala/ssbudget/frontend/Router.scala` - Settings route
- `frontend/src/main/scala/ssbudget/frontend/components/Layout.scala` - Pass apiClient
- `frontend/src/main/scala/ssbudget/frontend/components/NavBar.scala` - Logout button
- `frontend/src/main/scala/ssbudget/frontend/services/ApiClient.scala` - Auth API methods

### Shared
- `shared/src/main/scala/ssbudget/shared/api/Endpoints.scala` - Session cookie security
- `shared/src/main/scala/ssbudget/shared/api/TapirSchemas.scala` - Auth DTO schemas

## Code Review Fixes

During review, fixed several issues:
1. Thread-unsafe mutable vars in WebAuthnService -> Ref[IO, ...]
2. Cookie secure flag hardcoded -> configurable via env var
3. Code duplication in AuthState -> extracted fetchAndUpdateStatus()
4. SecureRandom created per token -> shared instance
5. Inconsistent API client endpoint -> consistent use of client endpoints

## Verification

- `sbt compile` - All modules compile
- `sbt backend/test` - 50 tests pass
- `sbt e2e/test` - 70 tests pass (including 6 auth tests)
- `sbt scalafmtAll` - Code formatted

## Auth Endpoints

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| GET | /api/auth/status | Optional | Check auth state |
| POST | /api/auth/setup | No | Initial password setup |
| POST | /api/auth/login | No | Password login |
| POST | /api/auth/logout | Optional | Logout |
| POST | /api/auth/passkey/register/start | Required | Start passkey registration |
| POST | /api/auth/passkey/register/finish | Required | Complete passkey registration |
| POST | /api/auth/passkey/login/start | No | Start passkey login |
| POST | /api/auth/passkey/login/finish | No | Complete passkey login |
| GET | /api/auth/passkeys | Required | List registered passkeys |
| DELETE | /api/auth/passkeys/:id | Required | Delete passkey |

## Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| SSBUDGET_RP_ID | localhost | WebAuthn Relying Party ID |
| SSBUDGET_RP_NAME | SSBudget | WebAuthn Relying Party name |
| SSBUDGET_RP_ORIGINS | http://localhost:3000,http://localhost:8080 | Allowed origins |
| SSBUDGET_COOKIE_SECURE | false | Set to true for HTTPS |
