package ssbudget.backend.auth

import cats.effect.{IO, Ref}
import com.yubico.webauthn.*
import com.yubico.webauthn.data.*
import ssbudget.backend.db.repository.{PasskeyCredential, PasskeyCredentialRepository}
import ssbudget.shared.api.*

import java.time.Instant
import java.util.{Base64, Optional}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

trait WebAuthnService {
  def startRegistration(displayName: Option[String]): IO[PasskeyRegistrationOptions]
  def finishRegistration(response: PasskeyRegistrationResponse): IO[Unit]
  def startAuthentication(): IO[PasskeyAuthenticationOptions]
  def finishAuthentication(response: PasskeyAuthenticationResponse): IO[Unit]
}

object WebAuthnService {

  private val UserId   = "ssbudget-user"
  private val UserName = "SSBudget User"

  def apply(
      credentialRepo: PasskeyCredentialRepository,
      rpId: String,
      rpName: String,
      rpOrigins: Set[String],
  ): IO[WebAuthnService] = {
    for {
      pendingRegRef  <- Ref.of[IO, Option[(PublicKeyCredentialCreationOptions, Option[String])]](None)
      pendingAuthRef <- Ref.of[IO, Option[AssertionRequest]](None)
    } yield new WebAuthnServiceImpl(credentialRepo, rpId, rpName, rpOrigins, pendingRegRef, pendingAuthRef)
  }

  private class WebAuthnServiceImpl(
      credentialRepo: PasskeyCredentialRepository,
      rpId: String,
      rpName: String,
      rpOrigins: Set[String],
      pendingRegistration: Ref[IO, Option[(PublicKeyCredentialCreationOptions, Option[String])]],
      pendingAuthentication: Ref[IO, Option[AssertionRequest]],
  ) extends WebAuthnService {

    private val rp = RelyingPartyIdentity
      .builder()
      .id(rpId)
      .name(rpName)
      .build()

    private def createRelyingParty(): IO[RelyingParty] = {
      credentialRepo.findAll.map { credentials =>
        val credentialRepository = new CredentialRepository {
          override def getCredentialIdsForUsername(username: String): java.util.Set[PublicKeyCredentialDescriptor] = {
            credentials
              .map { cred =>
                PublicKeyCredentialDescriptor
                  .builder()
                  .id(ByteArray.fromBase64Url(cred.credentialId))
                  .build()
              }
              .toSet
              .asJava
          }

          override def getUserHandleForUsername(username: String): Optional[ByteArray] = {
            Optional.of(ByteArray.fromBase64Url(Base64.getUrlEncoder.withoutPadding().encodeToString(UserId.getBytes)))
          }

          override def getUsernameForUserHandle(userHandle: ByteArray): Optional[String] = {
            Optional.of(UserName)
          }

          override def lookup(credentialId: ByteArray, userHandle: ByteArray): Optional[RegisteredCredential] = {
            credentials
              .find(_.credentialId == credentialId.getBase64Url)
              .map { cred =>
                RegisteredCredential
                  .builder()
                  .credentialId(ByteArray.fromBase64Url(cred.credentialId))
                  .userHandle(userHandle)
                  .publicKeyCose(new ByteArray(cred.publicKeyCose))
                  .signatureCount(cred.signCount)
                  .build()
              }
              .toJava
          }

          override def lookupAll(credentialId: ByteArray): java.util.Set[RegisteredCredential] = {
            lookup(credentialId, ByteArray.fromBase64Url(Base64.getUrlEncoder.withoutPadding().encodeToString(UserId.getBytes))).toScala.toSet.asJava
          }
        }

        RelyingParty
          .builder()
          .identity(rp)
          .credentialRepository(credentialRepository)
          .origins(rpOrigins.asJava)
          .build()
      }
    }

    override def startRegistration(displayName: Option[String]): IO[PasskeyRegistrationOptions] = {
      createRelyingParty().flatMap { relyingParty =>
        val userIdentity = UserIdentity
          .builder()
          .name(UserName)
          .displayName(displayName.getOrElse(UserName))
          .id(ByteArray.fromBase64Url(Base64.getUrlEncoder.withoutPadding().encodeToString(UserId.getBytes)))
          .build()

        val authenticatorSelection = AuthenticatorSelectionCriteria
          .builder()
          .residentKey(ResidentKeyRequirement.PREFERRED)
          .userVerification(UserVerificationRequirement.PREFERRED)
          .build()

        val options = relyingParty.startRegistration(
          StartRegistrationOptions
            .builder()
            .user(userIdentity)
            .authenticatorSelection(authenticatorSelection)
            .build(),
        )

        pendingRegistration.set(Some((options, displayName))).as {
          PasskeyRegistrationOptions(
            challenge = options.getChallenge.getBase64Url,
            rpId = rpId,
            rpName = rpName,
            userId = options.getUser.getId.getBase64Url,
            userName = options.getUser.getName,
            timeout = options.getTimeout.toScala.map(_.longValue()).getOrElse(60000L),
            attestation = options.getAttestation.getValue,
            authenticatorSelection = AuthenticatorSelection(
              authenticatorAttachment = options.getAuthenticatorSelection.toScala
                .flatMap(_.getAuthenticatorAttachment.toScala.map(_.getValue)),
              residentKey = options.getAuthenticatorSelection.toScala
                .flatMap(_.getResidentKey.toScala.map(_.getValue))
                .getOrElse("preferred"),
              userVerification = options.getAuthenticatorSelection.toScala
                .flatMap(_.getUserVerification.toScala.map(_.getValue))
                .getOrElse("preferred"),
            ),
            pubKeyCredParams = options.getPubKeyCredParams.asScala.toList.map { param =>
              PubKeyCredParam(param.getType.getId, param.getAlg.getId.toInt)
            },
          )
        }
      }
    }

    override def finishRegistration(response: PasskeyRegistrationResponse): IO[Unit] = {
      pendingRegistration.getAndSet(None).flatMap {
        case Some((options, displayName)) =>
          createRelyingParty().flatMap { relyingParty =>
            val clientResponse = PublicKeyCredential.parseRegistrationResponseJson(toRegistrationJson(response))

            val result = relyingParty.finishRegistration(
              FinishRegistrationOptions
                .builder()
                .request(options)
                .response(clientResponse)
                .build(),
            )

            val credential = PasskeyCredential(
              credentialId = result.getKeyId.getId.getBase64Url,
              publicKeyCose = result.getPublicKeyCose.getBytes,
              signCount = result.getSignatureCount,
              displayName = displayName,
              createdAt = Instant.now(),
              lastUsedAt = None,
            )

            credentialRepo.create(credential)
          }
        case None                         =>
          IO.raiseError(new Exception("No pending registration"))
      }
    }

    override def startAuthentication(): IO[PasskeyAuthenticationOptions] = {
      createRelyingParty().flatMap { relyingParty =>
        credentialRepo.findAll.flatMap { credentials =>
          val request = relyingParty.startAssertion(
            StartAssertionOptions.builder().build(),
          )

          pendingAuthentication.set(Some(request)).as {
            PasskeyAuthenticationOptions(
              challenge = request.getPublicKeyCredentialRequestOptions.getChallenge.getBase64Url,
              rpId = rpId,
              timeout = request.getPublicKeyCredentialRequestOptions.getTimeout.toScala.map(_.longValue()).getOrElse(60000L),
              userVerification = request.getPublicKeyCredentialRequestOptions.getUserVerification.toScala.map(_.getValue).getOrElse("preferred"),
              allowCredentials = credentials.map { cred =>
                AllowCredential(
                  `type` = "public-key",
                  id = cred.credentialId,
                  transports = None,
                )
              },
            )
          }
        }
      }
    }

    override def finishAuthentication(response: PasskeyAuthenticationResponse): IO[Unit] = {
      pendingAuthentication.getAndSet(None).flatMap {
        case Some(request) =>
          createRelyingParty().flatMap { relyingParty =>
            val clientResponse = PublicKeyCredential.parseAssertionResponseJson(toAssertionJson(response))

            val result = relyingParty.finishAssertion(
              FinishAssertionOptions
                .builder()
                .request(request)
                .response(clientResponse)
                .build(),
            )

            if result.isSuccess then {
              val credId = result.getCredential.getCredentialId.getBase64Url
              credentialRepo.updateSignCount(credId, result.getSignatureCount, Instant.now())
            } else {
              IO.raiseError(new Exception("Authentication failed"))
            }
          }
        case None          =>
          IO.raiseError(new Exception("No pending authentication"))
      }
    }

    private def toRegistrationJson(response: PasskeyRegistrationResponse): String = {
      import io.circe.syntax.*
      import io.circe.{Json, JsonObject}

      // The library expects a specific JSON format with clientExtensionResults
      val responseObj = JsonObject(
        "clientDataJSON"    -> Json.fromString(response.response.clientDataJSON),
        "attestationObject" -> Json.fromString(response.response.attestationObject),
      )

      val json = JsonObject(
        "id"                     -> Json.fromString(response.id),
        "rawId"                  -> Json.fromString(response.rawId),
        "type"                   -> Json.fromString(response.`type`),
        "response"               -> Json.fromJsonObject(responseObj),
        "clientExtensionResults" -> Json.fromJsonObject(JsonObject.empty),
      )

      Json.fromJsonObject(json).noSpaces
    }

    private def toAssertionJson(response: PasskeyAuthenticationResponse): String = {
      import io.circe.syntax.*
      import io.circe.{Json, JsonObject}

      val responseObj = JsonObject(
        "clientDataJSON"    -> Json.fromString(response.response.clientDataJSON),
        "authenticatorData" -> Json.fromString(response.response.authenticatorData),
        "signature"         -> Json.fromString(response.response.signature),
        "userHandle"        -> response.response.userHandle.map(Json.fromString).getOrElse(Json.Null),
      )

      val json = JsonObject(
        "id"                     -> Json.fromString(response.id),
        "rawId"                  -> Json.fromString(response.rawId),
        "type"                   -> Json.fromString(response.`type`),
        "response"               -> Json.fromJsonObject(responseObj),
        "clientExtensionResults" -> Json.fromJsonObject(JsonObject.empty),
      )

      Json.fromJsonObject(json).noSpaces
    }
  }
}
