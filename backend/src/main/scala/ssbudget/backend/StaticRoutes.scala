package ssbudget.backend

import cats.data.OptionT
import cats.effect.IO
import fs2.io.file.{Files, Path}
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

/** Serves static files from the frontend build directory */
object StaticRoutes {

  private val defaultMimeTypes: Map[String, MediaType] = Map(
    "html"  -> MediaType.text.html,
    "css"   -> MediaType.text.css,
    "js"    -> MediaType.application.javascript,
    "json"  -> MediaType.application.json,
    "png"   -> MediaType.image.png,
    "jpg"   -> MediaType.image.jpeg,
    "jpeg"  -> MediaType.image.jpeg,
    "gif"   -> MediaType.image.gif,
    "svg"   -> MediaType.image.`svg+xml`,
    "ico"   -> MediaType.image.`x-icon`,
    "woff"  -> MediaType.font.woff,
    "woff2" -> MediaType.font.woff2,
    "ttf"   -> MediaType.font.ttf,
    "eot"   -> MediaType.application.`vnd.ms-fontobject`,
    "map"   -> MediaType.application.json,
  )

  private def getMimeType(filename: String): MediaType = {
    val ext = filename.lastIndexOf('.') match {
      case -1 => ""
      case i  => filename.substring(i + 1).toLowerCase
    }
    defaultMimeTypes.getOrElse(ext, MediaType.application.`octet-stream`)
  }

  /** Create routes that serve static files from the given directory. Falls back to index.html for SPA routing (any path not matching a file).
    */
  def make(staticDir: Option[String]): HttpRoutes[IO] = {
    staticDir match {
      case None      =>
        HttpRoutes.empty[IO]
      case Some(dir) =>
        val basePath = Path(dir)
        HttpRoutes[IO] { req =>
          val path = req.uri.path.renderString
          // Skip API paths - let them fall through to API routes
          if path.startsWith("/api") then {
            OptionT.none[IO, Response[IO]]
          } else if req.method != Method.GET && req.method != Method.HEAD then {
            OptionT.none[IO, Response[IO]]
          } else {
            val requestedPath = req.uri.path.segments.mkString("/")
            val filePath      = if requestedPath.isEmpty then "index.html" else requestedPath

            OptionT.liftF(
              serveFile(basePath, filePath, req.method == Method.HEAD).getOrElseF {
                // SPA fallback: serve index.html for paths that don't match files
                // But only for paths that look like routes (no file extension)
                if !filePath.contains(".") then {
                  serveFile(basePath, "index.html", req.method == Method.HEAD).getOrElseF(NotFound())
                } else {
                  NotFound()
                }
              },
            )
          }
        }
    }
  }

  private def serveFile(basePath: Path, relativePath: String, headOnly: Boolean): OptionT[IO, Response[IO]] = {
    // Build path by joining segments properly (handles "assets/file.css" style paths)
    val filePath = relativePath.split("/").filter(_.nonEmpty).foldLeft(basePath)(_ / _)

    // Security: ensure the resolved path is still under basePath
    val normalizedBase = basePath.absolute.normalize
    val normalizedFile = filePath.absolute.normalize

    OptionT(
      if !normalizedFile.toString.startsWith(normalizedBase.toString) then {
        IO.pure(None)
      } else {
        Files[IO].exists(filePath).flatMap { exists =>
          if exists then {
            Files[IO].isRegularFile(filePath).flatMap { isFile =>
              if isFile then {
                val mediaType   = getMimeType(relativePath)
                val contentType = `Content-Type`(mediaType)
                if headOnly then {
                  // For HEAD requests, return headers without body
                  IO.pure(Some(Response[IO](Status.Ok).withContentType(contentType)))
                } else {
                  val body = Files[IO].readAll(filePath)
                  IO.pure(Some(Response[IO](Status.Ok).withEntity(body).withContentType(contentType)))
                }
              } else {
                IO.pure(None)
              }
            }
          } else {
            IO.pure(None)
          }
        }
      },
    )
  }
}
