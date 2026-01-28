package ssbudget.backend.auth

import cats.effect.IO
import ssbudget.backend.db.repository.{Session, SessionRepository}

import java.security.SecureRandom
import java.time.{Duration, Instant}
import java.util.Base64

trait SessionService {
  def createSession(): IO[Session]
  def validateSession(token: String): IO[Option[Session]]
  def invalidateSession(token: String): IO[Unit]
  def cleanupExpiredSessions(): IO[Int]
}

object SessionService {
  private val SessionDuration: Duration = Duration.ofDays(30)
  private val TokenLength: Int          = 32

  def apply(sessionRepository: SessionRepository): SessionService =
    new SessionServiceImpl(sessionRepository)

  private class SessionServiceImpl(sessionRepository: SessionRepository) extends SessionService {
    private val random = new SecureRandom()

    override def createSession(): IO[Session] = {
      for {
        token  <- generateToken()
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
        _           <- validSession.fold(IO.unit)(s => sessionRepository.updateLastUsed(s.token, now))
      } yield validSession
    }

    override def invalidateSession(token: String): IO[Unit] = {
      sessionRepository.delete(token)
    }

    override def cleanupExpiredSessions(): IO[Int] = {
      sessionRepository.deleteExpired(Instant.now())
    }

    private def generateToken(): IO[String] = IO.blocking {
      val bytes = new Array[Byte](TokenLength)
      random.nextBytes(bytes)
      Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
    }
  }
}
