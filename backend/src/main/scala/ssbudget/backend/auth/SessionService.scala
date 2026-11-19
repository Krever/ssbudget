package ssbudget.backend.auth

import cats.effect.IO
import ssbudget.backend.db.repository.{Session, SessionRepository}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.{Duration, Instant}

/** The logins of the owner.
  *
  * A session is a random token in a cookie. The database holds only the SHA-256 digest of the token, so a copy of the database (a backup, the export
  * on the Settings page) does not hold a working login. A plain digest is sufficient: the token has full entropy, and a session is found by its
  * digest. Every method of this trait takes the TOKEN, as the cookie has it.
  */
trait SessionService {

  /** Makes a session and returns its token, for the cookie. A login is a proof of identity, so the new session counts as confirmed. */
  def createSession(): IO[String]
  def validateSession(token: String): IO[Option[Session]]
  def invalidateSession(token: String): IO[Unit]
  def cleanupExpiredSessions(): IO[Int]

  /** End every session but the one with this token: for a password change, after which an old login must not work any more. */
  def invalidateOthers(keep: Option[String]): IO[Unit]

  /** Records that the owner of this session proved their identity now (password or passkey). */
  def confirm(token: String): IO[Unit]

  /** True when the session is valid and its owner proved their identity a short time ago (see `SessionService.ConfirmationWindow`). */
  def recentlyConfirmed(token: String): IO[Boolean]
}

object SessionService {
  private val SessionDuration: Duration = Duration.ofDays(30)
  private val TokenLength: Int          = 32
  private val LastUsedStep: Duration    = Duration.ofMinutes(1)

  /** How long a proof of identity is good for the actions that need one. Long enough for an export and an import in one sitting; short enough that a
    * session somebody finds open an hour later cannot do them.
    */
  val ConfirmationWindow: Duration = Duration.ofMinutes(10)

  def apply(sessionRepository: SessionRepository): SessionService =
    new SessionServiceImpl(sessionRepository)

  private[auth] def digest(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8)).map("%02x".format(_)).mkString

  private class SessionServiceImpl(sessionRepository: SessionRepository) extends SessionService {
    override def createSession(): IO[String] = {
      for {
        token  <- SecureToken.generate(TokenLength)
        now     = Instant.now()
        expires = now.plus(SessionDuration)
        _      <- sessionRepository.create(Session(digest(token), now, expires, now, confirmedAt = Some(now)))
      } yield token
    }

    override def validateSession(token: String): IO[Option[Session]] = {
      for {
        sessionOpt  <- sessionRepository.findByToken(digest(token))
        now          = Instant.now()
        validSession = sessionOpt.filter(s => s.expiresAt.isAfter(now))
        // "Last used" is written one time in a minute at most: with a write on every request, each page load would queue behind the one
        // database connection for nothing.
        _           <- validSession
                         .filter(s => Duration.between(s.lastUsedAt, now).compareTo(LastUsedStep) > 0)
                         .fold(IO.unit)(s => sessionRepository.updateLastUsed(s.token, now))
      } yield validSession
    }

    override def invalidateSession(token: String): IO[Unit] = {
      sessionRepository.delete(digest(token))
    }

    override def cleanupExpiredSessions(): IO[Int] = {
      sessionRepository.deleteExpired(Instant.now())
    }

    override def invalidateOthers(keep: Option[String]): IO[Unit] = sessionRepository.deleteAllExcept(keep.map(digest)).void

    override def confirm(token: String): IO[Unit] = sessionRepository.setConfirmed(digest(token), Instant.now())

    override def recentlyConfirmed(token: String): IO[Boolean] =
      validateSession(token).map(_.flatMap(_.confirmedAt).exists(at => Duration.between(at, Instant.now()).compareTo(ConfirmationWindow) < 0))
  }
}
