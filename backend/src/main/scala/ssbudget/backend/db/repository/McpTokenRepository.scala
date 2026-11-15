package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given

import java.time.Instant

/** One MCP access token as stored: its digest, never the token. */
final case class McpTokenRow(
    id: String,
    name: String,
    tokenHash: String,
    createdAt: Instant,
    lastUsedAt: Option[Instant],
)

trait McpTokenRepository {
  def create(row: McpTokenRow): IO[Unit]
  def findAll: IO[List[McpTokenRow]]
  def findByHash(tokenHash: String): IO[Option[McpTokenRow]]
  def updateLastUsed(id: String, at: Instant): IO[Unit]
  def delete(id: String): IO[Unit]
  def count: IO[Int]
}

class McpTokenRepositoryImpl(xa: Transactor[IO]) extends McpTokenRepository {

  private val columns = fr"id, name, token_hash, created_at, last_used_at"

  override def create(row: McpTokenRow): IO[Unit] =
    sql"""
      INSERT INTO mcp_tokens (id, name, token_hash, created_at, last_used_at)
      VALUES (${row.id}, ${row.name}, ${row.tokenHash}, ${row.createdAt}, ${row.lastUsedAt})
    """.update.run.transact(xa).void

  override def findAll: IO[List[McpTokenRow]] =
    (fr"SELECT" ++ columns ++ fr"FROM mcp_tokens ORDER BY created_at").query[McpTokenRow].to[List].transact(xa)

  override def findByHash(tokenHash: String): IO[Option[McpTokenRow]] =
    (fr"SELECT" ++ columns ++ fr"FROM mcp_tokens WHERE token_hash = $tokenHash").query[McpTokenRow].option.transact(xa)

  override def updateLastUsed(id: String, at: Instant): IO[Unit] =
    sql"UPDATE mcp_tokens SET last_used_at = $at WHERE id = $id".update.run.transact(xa).void

  override def delete(id: String): IO[Unit] =
    sql"DELETE FROM mcp_tokens WHERE id = $id".update.run.transact(xa).void

  override def count: IO[Int] =
    sql"SELECT COUNT(*) FROM mcp_tokens".query[Int].unique.transact(xa)
}
