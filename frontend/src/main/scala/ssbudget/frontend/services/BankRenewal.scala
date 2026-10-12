package ssbudget.frontend.services

import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*
import org.scalajs.dom
import ssbudget.shared.api.ConnectBankRequest
import ssbudget.shared.model.BankConnection

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

/** The one way into a bank login, for a first connection and for renewing the consent of one or more banks in a single run. Each bank needs its own
  * login at the bank, and each login leaves the app, so the banks still to do and the page to come back to are kept in `sessionStorage` (it lives
  * through the round trip in this tab). The callback page calls [[continue]]: it sends the user to the next bank, or back to where the run started.
  *
  * A renewal is an ordinary new connection: on its callback the backend folds the old connection of the same bank into it (history and links kept).
  */
object BankRenewal {

  private val queueKey  = "ssbudget.bankRenewal.queue"
  private val returnKey = "ssbudget.bankRenewal.returnTo"

  /** A bank to log in to; the name and country are all that the connect flow needs. */
  final case class Bank(name: String, country: String) derives Codec.AsObject

  object Bank {
    def of(conn: BankConnection): Bank = Bank(conn.aspspName, conn.aspspCountry)
  }

  /** Starts a run from the current page: goes to the first bank now and keeps the others for after its callback. */
  def start(apiClient: ApiClient, banks: List[Bank])(onError: Throwable => Unit): Unit =
    banks.distinct match {
      case Nil           => ()
      case first :: rest =>
        writeQueue(rest)
        write(returnKey, dom.window.location.pathname + dom.window.location.search)
        go(apiClient, first)(onError)
    }

  /** Goes to the next bank's login and returns it, or (`None`) back to the page the run started on — the banking page when there was no run. A
    * failure to start the login stops the run, so the user is never sent on past an error they have not seen.
    */
  def continue(apiClient: ApiClient)(onError: Throwable => Unit): Option[Bank] =
    queue match {
      case next :: rest =>
        writeQueue(rest)
        go(apiClient, next)(onError)
        Some(next)
      case Nil          =>
        val back = read(returnKey).filter(_.nonEmpty).getOrElse("/banking")
        clear()
        dom.window.location.href = back
        None
    }

  /** Stops a run, e.g. when a callback fails. */
  def clear(): Unit = {
    remove(queueKey)
    remove(returnKey)
  }

  private def go(apiClient: ApiClient, bank: Bank)(onError: Throwable => Unit): Unit =
    apiClient.banking.connect(ConnectBankRequest(bank.name, bank.country)).onComplete {
      case Success(res) => dom.window.location.href = res.redirectUrl // off to the bank for SCA
      case Failure(ex)  => clear(); onError(ex)
    }

  private def queue: List[Bank]                   = read(queueKey).flatMap(decode[List[Bank]](_).toOption).getOrElse(Nil)
  private def writeQueue(banks: List[Bank]): Unit = write(queueKey, banks.asJson.noSpaces)

  // Storage can be missing or blocked (private window); a run then just stops after the first bank.
  private def read(key: String): Option[String]       = Try(Option(dom.window.sessionStorage.getItem(key))).toOption.flatten
  private def write(key: String, value: String): Unit = Try(dom.window.sessionStorage.setItem(key, value))
  private def remove(key: String): Unit               = Try(dom.window.sessionStorage.removeItem(key))
}
