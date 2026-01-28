package ssbudget.frontend.auth

import com.raquo.laminar.api.L.*
import ssbudget.frontend.services.ApiClient

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

sealed trait AuthState
object AuthState {
  case object Loading                         extends AuthState
  case object NeedsSetup                      extends AuthState
  case class NeedsLogin(hasPasskeys: Boolean) extends AuthState
  case object LoggedIn                        extends AuthState
  case class Error(message: String)           extends AuthState

  val current: Var[AuthState] = Var(Loading)

  def initialize(apiClient: ApiClient)(implicit ec: ExecutionContext): Future[Unit] = {
    current.set(Loading)
    fetchAndUpdateStatus(apiClient)
  }

  private def fetchAndUpdateStatus(apiClient: ApiClient)(implicit ec: ExecutionContext): Future[Unit] = {
    apiClient.auth.status().transform {
      case Success(status) =>
        if !status.configured then {
          current.set(NeedsSetup)
        } else if status.loggedIn then {
          current.set(LoggedIn)
        } else {
          current.set(NeedsLogin(status.passkeyCount > 0))
        }
        Success(())
      case Failure(ex)     =>
        current.set(Error(s"Failed to check auth status: ${ex.getMessage}"))
        Success(())
    }
  }

  def logout(apiClient: ApiClient)(implicit ec: ExecutionContext): Future[Unit] = {
    apiClient.auth.logout().transform {
      case Success(_)  =>
        apiClient.auth.status().onComplete {
          case Success(status) =>
            current.set(NeedsLogin(status.passkeyCount > 0))
          case Failure(_)      =>
            current.set(NeedsLogin(false))
        }
        Success(())
      case Failure(ex) =>
        current.set(Error(s"Logout failed: ${ex.getMessage}"))
        Success(())
    }
  }

  def setLoggedIn(): Unit = {
    current.set(LoggedIn)
  }

  def refreshStatus(apiClient: ApiClient)(implicit ec: ExecutionContext): Future[Unit] = {
    fetchAndUpdateStatus(apiClient)
  }
}
