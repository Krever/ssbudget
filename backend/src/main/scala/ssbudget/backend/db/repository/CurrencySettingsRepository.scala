package ssbudget.backend.db.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import ssbudget.backend.db.DoobieMeta.given
import ssbudget.shared.model.*

trait CurrencySettingsRepository {
  def findAll: IO[List[CurrencySetting]]
  def findByCode(code: String): IO[Option[CurrencySetting]]
  def findPrimary: IO[Option[CurrencySetting]]
  def create(setting: CurrencySetting): IO[Unit]
  def setPrimary(code: String): IO[Unit]
  def delete(code: String): IO[Unit]
}

class CurrencySettingsRepositoryImpl(xa: Transactor[IO]) extends CurrencySettingsRepository {

  override def findAll: IO[List[CurrencySetting]] = {
    sql"""
      SELECT code, name, is_primary, enabled_at
      FROM currency_settings
      ORDER BY is_primary DESC, code ASC
    """.query[CurrencySetting].to[List].transact(xa)
  }

  override def findByCode(code: String): IO[Option[CurrencySetting]] = {
    sql"""
      SELECT code, name, is_primary, enabled_at
      FROM currency_settings
      WHERE code = $code
    """.query[CurrencySetting].option.transact(xa)
  }

  override def findPrimary: IO[Option[CurrencySetting]] = {
    sql"""
      SELECT code, name, is_primary, enabled_at
      FROM currency_settings
      WHERE is_primary = 1
    """.query[CurrencySetting].option.transact(xa)
  }

  override def create(setting: CurrencySetting): IO[Unit] = {
    sql"""
      INSERT INTO currency_settings (code, name, is_primary, enabled_at)
      VALUES (${setting.code}, ${setting.name}, ${setting.isPrimary}, ${setting.enabledAt})
    """.update.run.transact(xa).void
  }

  override def setPrimary(code: String): IO[Unit] = {
    // Transaction: clear all primary flags, then set the new one
    val ops = for {
      _ <- sql"UPDATE currency_settings SET is_primary = 0".update.run
      _ <- sql"UPDATE currency_settings SET is_primary = 1 WHERE code = $code".update.run
    } yield ()

    ops.transact(xa)
  }

  override def delete(code: String): IO[Unit] = {
    sql"""
      DELETE FROM currency_settings WHERE code = $code
    """.update.run.transact(xa).void
  }
}
