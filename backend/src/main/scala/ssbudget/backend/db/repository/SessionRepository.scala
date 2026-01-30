package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given

import java.time.Instant

final case class Session(
    token: String,
    createdAt: Instant,
    expiresAt: Instant,
    lastUsedAt: Instant,
)

trait SessionRepository {
  def create(session: Session): IO[Unit]
  def findByToken(token: String): IO[Option[Session]]
  def updateLastUsed(token: String, lastUsedAt: Instant): IO[Unit]
  def delete(token: String): IO[Unit]
  def deleteExpired(now: Instant): IO[Int]
}

class SessionRepositoryImpl(xa: Transactor[IO]) extends SessionRepository {

  override def create(session: Session): IO[Unit] = {
    sql"""
      INSERT INTO sessions (token, created_at, expires_at, last_used_at)
      VALUES (${session.token}, ${session.createdAt}, ${session.expiresAt}, ${session.lastUsedAt})
    """.update.run.transact(xa).void
  }

  override def findByToken(token: String): IO[Option[Session]] = {
    sql"""
      SELECT token, created_at, expires_at, last_used_at FROM sessions WHERE token = $token
    """.query[Session].option.transact(xa)
  }

  override def updateLastUsed(token: String, lastUsedAt: Instant): IO[Unit] = {
    sql"""
      UPDATE sessions SET last_used_at = $lastUsedAt WHERE token = $token
    """.update.run.transact(xa).void
  }

  override def delete(token: String): IO[Unit] = {
    sql"""
      DELETE FROM sessions WHERE token = $token
    """.update.run.transact(xa).void
  }

  override def deleteExpired(now: Instant): IO[Int] = {
    sql"""
      DELETE FROM sessions WHERE expires_at < $now
    """.update.run.transact(xa)
  }
}
