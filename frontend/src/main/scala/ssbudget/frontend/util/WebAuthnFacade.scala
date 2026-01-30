package ssbudget.frontend.util

import org.scalajs.dom
import ssbudget.shared.api.*

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}

object WebAuthnFacade {

  def isSupported: Boolean = {
    !js.isUndefined(dom.window.asInstanceOf[js.Dynamic].PublicKeyCredential)
  }

  def createCredential(
      options: PasskeyRegistrationOptions,
  )(implicit ec: ExecutionContext): Future[PasskeyRegistrationResponse] = {
    val publicKeyOptions = js.Dynamic.literal(
      challenge = base64UrlToArrayBuffer(options.challenge),
      rp = js.Dynamic.literal(
        id = options.rpId,
        name = options.rpName,
      ),
      user = js.Dynamic.literal(
        id = base64UrlToArrayBuffer(options.userId),
        name = options.userName,
        displayName = options.userName,
      ),
      pubKeyCredParams = js.Array(
        options.pubKeyCredParams.map { param =>
          js.Dynamic.literal(
            `type` = param.`type`,
            alg = param.alg,
          )
        }*,
      ),
      timeout = options.timeout.toDouble,
      attestation = options.attestation,
      authenticatorSelection = js.Dynamic.literal(
        authenticatorAttachment = options.authenticatorSelection.authenticatorAttachment.orNull,
        residentKey = options.authenticatorSelection.residentKey,
        userVerification = options.authenticatorSelection.userVerification,
      ),
    )

    val createOptions = js.Dynamic.literal(
      publicKey = publicKeyOptions,
    )

    val credentials = dom.window.navigator.asInstanceOf[js.Dynamic].credentials
    credentials
      .create(createOptions)
      .asInstanceOf[js.Promise[js.Dynamic]]
      .toFuture
      .map { credential =>
        val response = credential.response
        val rawId    = credential.rawId.asInstanceOf[ArrayBuffer]
        val id       = credential.id.asInstanceOf[String]

        val clientDataJSON    = arrayBufferToBase64Url(response.clientDataJSON.asInstanceOf[ArrayBuffer])
        val attestationObject = arrayBufferToBase64Url(response.attestationObject.asInstanceOf[ArrayBuffer])

        val transports: Option[List[String]] = {
          if !js.isUndefined(response.getTransports) then {
            val arr = response.getTransports().asInstanceOf[js.Array[String]]
            Some(arr.toList)
          } else {
            None
          }
        }

        PasskeyRegistrationResponse(
          id = id,
          rawId = arrayBufferToBase64Url(rawId),
          response = AttestationResponse(
            clientDataJSON = clientDataJSON,
            attestationObject = attestationObject,
            transports = transports,
          ),
          `type` = "public-key",
          clientExtensionResults = None,
        )
      }
  }

  def getCredential(
      options: PasskeyAuthenticationOptions,
  )(implicit ec: ExecutionContext): Future[PasskeyAuthenticationResponse] = {
    val allowCredentials = js.Array(
      options.allowCredentials.map { cred =>
        js.Dynamic.literal(
          `type` = cred.`type`,
          id = base64UrlToArrayBuffer(cred.id),
          transports = cred.transports.map(t => js.Array(t*)).getOrElse(js.undefined),
        )
      }*,
    )

    val publicKeyOptions = js.Dynamic.literal(
      challenge = base64UrlToArrayBuffer(options.challenge),
      rpId = options.rpId,
      timeout = options.timeout.toDouble,
      userVerification = options.userVerification,
      allowCredentials = allowCredentials,
    )

    val getOptions = js.Dynamic.literal(
      publicKey = publicKeyOptions,
    )

    val credentials = dom.window.navigator.asInstanceOf[js.Dynamic].credentials
    credentials
      .get(getOptions)
      .asInstanceOf[js.Promise[js.Dynamic]]
      .toFuture
      .map { credential =>
        val response = credential.response
        val rawId    = credential.rawId.asInstanceOf[ArrayBuffer]
        val id       = credential.id.asInstanceOf[String]

        val clientDataJSON    = arrayBufferToBase64Url(response.clientDataJSON.asInstanceOf[ArrayBuffer])
        val authenticatorData = arrayBufferToBase64Url(response.authenticatorData.asInstanceOf[ArrayBuffer])
        val signature         = arrayBufferToBase64Url(response.signature.asInstanceOf[ArrayBuffer])
        val userHandle        = if js.isUndefined(response.userHandle) || response.userHandle == null then {
          None
        } else {
          Some(arrayBufferToBase64Url(response.userHandle.asInstanceOf[ArrayBuffer]))
        }

        PasskeyAuthenticationResponse(
          id = id,
          rawId = arrayBufferToBase64Url(rawId),
          response = AssertionResponse(
            clientDataJSON = clientDataJSON,
            authenticatorData = authenticatorData,
            signature = signature,
            userHandle = userHandle,
          ),
          `type` = "public-key",
          clientExtensionResults = None,
        )
      }
  }

  private def base64UrlToArrayBuffer(base64url: String): ArrayBuffer = {
    // Convert base64url to standard base64
    val base64 = base64url.replace('-', '+').replace('_', '/')
    val padded = base64 + "=" * ((4 - base64.length % 4) % 4)

    val binary = dom.window.atob(padded)
    val bytes  = new Uint8Array(binary.length)
    for i <- 0 until binary.length do {
      bytes(i) = binary.charAt(i).toByte
    }
    bytes.buffer
  }

  private def arrayBufferToBase64Url(buffer: ArrayBuffer): String = {
    val bytes  = new Uint8Array(buffer)
    val binary = (0 until bytes.length).map(i => bytes(i).toChar).mkString
    val base64 = dom.window.btoa(binary)
    // Convert to base64url
    base64.replace('+', '-').replace('/', '_').replace("=", "")
  }
}
