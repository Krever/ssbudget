package ssbudget.backend.service

import cats.effect.IO
import doobie.hikari.HikariTransactor
import org.sqlite.SQLiteConnection
import ssbudget.backend.db.Database
import ssbudget.shared.api.ApiError

import java.nio.file.{Files as JFiles, Paths}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The whole database as one file: to give a copy of it (export), and to put such a file in the place of the live database (import).
  *
  * A refused file is an error of the kind "invalid"; a fault of the copy or of the restore is of the kind "failed".
  */
class BackupService(xa: HikariTransactor[IO], dbPath: String) {

  private type Result[A] = IO[Either[ApiError, A]]

  /** A consistent copy of the whole database, via SQLite's own backup API.
    *
    * NOT a raw read of the database file: this database runs in WAL mode, where committed data lives in the `-wal` sidecar until a checkpoint.
    * Copying the main file alone therefore produced a backup that was stale, or — on a freshly checkpointed file — empty, while still carrying a
    * valid SQLite header, so nothing looked wrong until a restore came up missing its tables.
    */
  def exportDatabase: Result[(String, Array[Byte])] = {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
    val filename  = s"ssbudget_backup_$timestamp.db"
    val tempPath  = Paths.get(dbPath + ".export.tmp")

    val snapshot = IO.blocking {
      // Close the POOLED connection, not the unwrapped delegate — see importDatabase.
      val pooled = xa.kernel.getConnection
      try {
        pooled.unwrap(classOf[SQLiteConnection]).getDatabase.backup("main", tempPath.toAbsolutePath.toString, null)
      } finally {
        pooled.close()
      }
      JFiles.readAllBytes(tempPath)
    }

    val cleanup = IO.blocking(if JFiles.exists(tempPath) then JFiles.delete(tempPath))

    snapshot
      .guarantee(cleanup)
      .map(bytes => Right((s"""attachment; filename="$filename"""", bytes)))
      .handleError(e => Left(ApiError.failed(s"Export failed: ${e.getMessage}")))
  }

  def importDatabase(bytes: Array[Byte]): Result[String] = {
    val tempPath = Paths.get(dbPath + ".import.tmp")

    // Validate SQLite header
    def isValidSqlite: Boolean = {
      if bytes.length >= 16 then {
        val header   = bytes.take(16)
        val expected = "SQLite format 3\u0000".getBytes("UTF-8")
        header.sameElements(expected)
      } else {
        false
      }
    }

    if !isValidSqlite then {
      IO.pure(Left(ApiError.invalid("Invalid SQLite file. Upload must be a valid SQLite database.")))
    } else {
      val writeTemp = IO.blocking {
        val parentDir = tempPath.getParent
        if parentDir != null && !JFiles.exists(parentDir) then {
          val _ = JFiles.createDirectories(parentDir)
        }
        JFiles.write(tempPath, bytes)
      }

      // The schema versions in the uploaded file. A file with no Flyway history is not a database of this app. A file with a version that this
      // build does not know comes from a newer build: this build cannot run on it, and the next start would fail.
      val uploadedVersions: IO[Either[String, Set[String]]] = IO.blocking {
        val connection = java.sql.DriverManager.getConnection(s"jdbc:sqlite:${tempPath.toAbsolutePath}")
        try {
          val rows     = connection.createStatement().executeQuery("SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL")
          val versions = Iterator.continually(rows).takeWhile(_.next()).map(_.getString(1)).toSet
          Right(versions): Either[String, Set[String]]
        } catch {
          case _: java.sql.SQLException => Left("This file is not an SSBudget database: it has no migration history.")
        } finally connection.close()
      }

      def checked(known: Set[String]): IO[Either[String, Unit]] = uploadedVersions.map(_.flatMap { uploaded =>
        val unknown = uploaded -- known
        Either.cond(
          unknown.isEmpty,
          (),
          s"This backup is from a newer version of the app (schema ${unknown.toList.sorted.mkString(", ")}). Update the app first.",
        )
      })

      // One copy of the database as it was, next to it. A wrong file in the import box is then not the end of the data.
      val keepCurrent = IO.blocking {
        val before = Paths.get(dbPath + ".before-import")
        JFiles.deleteIfExists(before)
        val pooled = xa.kernel.getConnection
        try {
          pooled.unwrap(classOf[SQLiteConnection]).getDatabase.backup("main", before.toAbsolutePath.toString, null)
        } finally {
          pooled.close()
        }
        ()
      }

      val restoreDb = IO.blocking {
        // Close the POOLED connection, not the unwrapped SQLite one. `unwrap` hands back the underlying delegate; closing that shuts the physical
        // connection without ever returning the pool's entry, so every import leaked the pool's only connection and left the app hanging on the next
        // query until it was restarted.
        val pooled = xa.kernel.getConnection
        try {
          pooled.unwrap(classOf[SQLiteConnection]).getDatabase.restore("main", tempPath.toAbsolutePath.toString, null)
        } finally {
          pooled.close()
        }
      }

      // The upload, and the two side files that SQLite makes when the upload is opened for the check.
      val cleanupTemp = IO.blocking {
        List("", "-shm", "-wal").foreach(suffix => JFiles.deleteIfExists(Paths.get(tempPath.toString + suffix)))
      }

      val work = for {
        _       <- writeTemp
        known   <- Database.knownVersions(xa)
        allowed <- checked(known)
        result  <- allowed match {
                     case Left(reason) => IO.pure(Left(ApiError.invalid(reason)))
                     case Right(())    =>
                       // An older backup gets the migrations it lacks at once, so the code never runs on an old schema.
                       (keepCurrent *> restoreDb *> Database.migrate(xa))
                         .as(Right("Database imported successfully. Please refresh the page to see the updated data."))
                   }
      } yield result

      work.guarantee(cleanupTemp.handleError(_ => ())).handleError(e => Left(ApiError.failed(s"Import failed: ${e.getMessage}")))
    }
  }
}
