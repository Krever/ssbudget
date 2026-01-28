package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given

import java.time.Instant

final case class AuthConfig(
    passwordHash: Option[String],
    createdAt: Instant,
    updatedAt: Instant,
)

trait AuthConfigRepository {
  def get: IO[Option[AuthConfig]]
  def upsert(passwordHash: String): IO[Unit]
}

class AuthConfigRepositoryImpl(xa: Transactor[IO]) extends AuthConfigRepository {

  override def get: IO[Option[AuthConfig]] = {
    sql"""
      SELECT password_hash, created_at, updated_at FROM auth_config WHERE id = 1
    """.query[AuthConfig].option.transact(xa)
  }

  override def upsert(passwordHash: String): IO[Unit] = {
    val now = Instant.now()
    sql"""
      INSERT INTO auth_config (id, password_hash, created_at, updated_at)
      VALUES (1, $passwordHash, $now, $now)
      ON CONFLICT(id) DO UPDATE SET password_hash = $passwordHash, updated_at = $now
    """.update.run.transact(xa).void
  }
}
