package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given

import java.time.Instant

final case class PasskeyCredential(
    credentialId: String,
    publicKeyCose: Array[Byte],
    signCount: Long,
    displayName: Option[String],
    createdAt: Instant,
    lastUsedAt: Option[Instant],
)

trait PasskeyCredentialRepository {
  def create(credential: PasskeyCredential): IO[Unit]
  def findById(credentialId: String): IO[Option[PasskeyCredential]]
  def findAll: IO[List[PasskeyCredential]]
  def updateSignCount(credentialId: String, signCount: Long, lastUsedAt: Instant): IO[Unit]
  def delete(credentialId: String): IO[Unit]
  def count: IO[Int]
}

class PasskeyCredentialRepositoryImpl(xa: Transactor[IO]) extends PasskeyCredentialRepository {

  override def create(credential: PasskeyCredential): IO[Unit] = {
    sql"""
      INSERT INTO passkey_credentials (credential_id, public_key_cose, sign_count, display_name, created_at, last_used_at)
      VALUES (${credential.credentialId}, ${credential.publicKeyCose}, ${credential.signCount}, ${credential.displayName}, ${credential.createdAt}, ${credential.lastUsedAt})
    """.update.run.transact(xa).void
  }

  override def findById(credentialId: String): IO[Option[PasskeyCredential]] = {
    sql"""
      SELECT credential_id, public_key_cose, sign_count, display_name, created_at, last_used_at
      FROM passkey_credentials WHERE credential_id = $credentialId
    """.query[PasskeyCredential].option.transact(xa)
  }

  override def findAll: IO[List[PasskeyCredential]] = {
    sql"""
      SELECT credential_id, public_key_cose, sign_count, display_name, created_at, last_used_at
      FROM passkey_credentials ORDER BY created_at DESC
    """.query[PasskeyCredential].to[List].transact(xa)
  }

  override def updateSignCount(credentialId: String, signCount: Long, lastUsedAt: Instant): IO[Unit] = {
    sql"""
      UPDATE passkey_credentials SET sign_count = $signCount, last_used_at = $lastUsedAt
      WHERE credential_id = $credentialId
    """.update.run.transact(xa).void
  }

  override def delete(credentialId: String): IO[Unit] = {
    sql"""
      DELETE FROM passkey_credentials WHERE credential_id = $credentialId
    """.update.run.transact(xa).void
  }

  override def count: IO[Int] = {
    sql"""
      SELECT COUNT(*) FROM passkey_credentials
    """.query[Int].unique.transact(xa)
  }
}
