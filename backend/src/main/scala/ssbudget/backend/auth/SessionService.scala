package ssbudget.backend.auth

import cats.effect.IO
import ssbudget.backend.db.repository.{Session, SessionRepository}

import java.time.{Duration, Instant}

trait SessionService {
  def createSession(): IO[Session]
  def validateSession(token: String): IO[Option[Session]]
  def invalidateSession(token: String): IO[Unit]
  def cleanupExpiredSessions(): IO[Int]

  /** End every session but the one with this token: for a password change, after which an old login must not work any more. */
  def invalidateOthers(keep: Option[String]): IO[Unit]
}

object SessionService {
  private val SessionDuration: Duration = Duration.ofDays(30)
  private val TokenLength: Int          = 32
  private val LastUsedStep: Duration    = Duration.ofMinutes(1)

  def apply(sessionRepository: SessionRepository): SessionService =
    new SessionServiceImpl(sessionRepository)

  private class SessionServiceImpl(sessionRepository: SessionRepository) extends SessionService {
    override def createSession(): IO[Session] = {
      for {
        token  <- SecureToken.generate(TokenLength)
        now     = Instant.now()
        expires = now.plus(SessionDuration)
        session = Session(token, now, expires, now)
        _      <- sessionRepository.create(session)
      } yield session
    }

    override def validateSession(token: String): IO[Option[Session]] = {
      for {
        sessionOpt  <- sessionRepository.findByToken(token)
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
      sessionRepository.delete(token)
    }

    override def cleanupExpiredSessions(): IO[Int] = {
      sessionRepository.deleteExpired(Instant.now())
    }

    override def invalidateOthers(keep: Option[String]): IO[Unit] = sessionRepository.deleteAllExcept(keep).void

  }
}
