package ssbudget.shared.api

import sttp.model.StatusCode
import sttp.tapir.*

/** Why a request was refused. The client acts on the kind (a dead session brings the login back) and shows the message to the user.
  *
  * On the wire an error is an HTTP status and a plain text, and nothing more: the status is the kind, the text is the message. Before, every error
  * was a text with status 400, and the code could not tell "the session ended" from "that name is taken" but by the words.
  */
enum ErrorKind(val status: Int) {

  /** The request is understood and the app refuses it: an input that is not valid, or a rule of the app that it breaks. */
  case Invalid extends ErrorKind(400)

  /** No valid session. */
  case Unauthorized extends ErrorKind(401)

  /** The session is valid, and the action needs a recent proof of identity that the session does not have. */
  case NeedsConfirmation extends ErrorKind(403)

  /** The thing that the request names does not exist. */
  case NotFound extends ErrorKind(404)

  /** The app failed: a fault on the server, not a wrong request. */
  case Failed extends ErrorKind(500)
}

object ErrorKind {

  /** The kind that a status stands for. A status that the app does not send (a proxy on the way can answer too) is a refusal when it is a client
    * error, and a failure in every other case.
    */
  def ofStatus(status: Int): ErrorKind =
    values.find(_.status == status).getOrElse(if status >= 400 && status < 500 then Invalid else Failed)
}

final case class ApiError(kind: ErrorKind, message: String)

object ApiError {
  def invalid(message: String): ApiError  = ApiError(ErrorKind.Invalid, message)
  def notFound(message: String): ApiError = ApiError(ErrorKind.NotFound, message)
  def failed(message: String): ApiError   = ApiError(ErrorKind.Failed, message)
  val unauthorized: ApiError              = ApiError(ErrorKind.Unauthorized, "Unauthorized")
  val needsConfirmation: ApiError         =
    ApiError(ErrorKind.NeedsConfirmation, "Confirm your identity first: this action needs the password or a passkey again.")

  /** The error of every endpoint: the status is the kind, the body is the message. */
  val output: EndpointOutput[ApiError] =
    statusCode
      .and(stringBody)
      .map { case (status, message) => ApiError(ErrorKind.ofStatus(status.code), message) }(e => (StatusCode(e.kind.status), e.message))
}
