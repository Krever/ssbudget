package ssbudget.shared.api

import io.circe.Codec

import java.time.Instant

// Auth status response
final case class AuthStatus(
    configured: Boolean,
    passkeyCount: Int,
    loggedIn: Boolean,
) derives Codec.AsObject

// Password setup request (initial setup only)
final case class SetupRequest(password: String) derives Codec.AsObject

// Password login request
final case class LoginRequest(password: String) derives Codec.AsObject

// Passkey info for listing
final case class PasskeyInfo(
    credentialId: String,
    displayName: Option[String],
    createdAt: Instant,
    lastUsedAt: Option[Instant],
) derives Codec.AsObject

// Passkey registration start request
final case class PasskeyRegisterStartRequest(displayName: Option[String]) derives Codec.AsObject

// Passkey registration options (returned from server)
final case class PasskeyRegistrationOptions(
    challenge: String,
    rpId: String,
    rpName: String,
    userId: String,
    userName: String,
    timeout: Long,
    attestation: String,
    authenticatorSelection: AuthenticatorSelection,
    pubKeyCredParams: List[PubKeyCredParam],
) derives Codec.AsObject

final case class AuthenticatorSelection(
    authenticatorAttachment: Option[String],
    residentKey: String,
    userVerification: String,
) derives Codec.AsObject

final case class PubKeyCredParam(
    `type`: String,
    alg: Int,
) derives Codec.AsObject

// Passkey registration response (from browser)
final case class PasskeyRegistrationResponse(
    id: String,
    rawId: String,
    response: AttestationResponse,
    `type`: String,
    clientExtensionResults: Option[Map[String, String]],
) derives Codec.AsObject

final case class AttestationResponse(
    clientDataJSON: String,
    attestationObject: String,
    transports: Option[List[String]],
) derives Codec.AsObject

// Passkey authentication options (returned from server)
final case class PasskeyAuthenticationOptions(
    challenge: String,
    rpId: String,
    timeout: Long,
    userVerification: String,
    allowCredentials: List[AllowCredential],
) derives Codec.AsObject

final case class AllowCredential(
    `type`: String,
    id: String,
    transports: Option[List[String]],
) derives Codec.AsObject

// Passkey authentication response (from browser)
final case class PasskeyAuthenticationResponse(
    id: String,
    rawId: String,
    response: AssertionResponse,
    `type`: String,
    clientExtensionResults: Option[Map[String, String]],
) derives Codec.AsObject

final case class AssertionResponse(
    clientDataJSON: String,
    authenticatorData: String,
    signature: String,
    userHandle: Option[String],
) derives Codec.AsObject
