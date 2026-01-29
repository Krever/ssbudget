package ssbudget.e2e

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.Port
import ssbudget.backend.ServerBuilder
import ssbudget.backend.db.{Database, Repositories}

import java.io.File
import java.net.{HttpURLConnection, ServerSocket, URL}
import java.nio.file.{Files, Path}
import scala.sys.process.{Process, ProcessLogger}
import scala.util.{Try, Using}

/** Test servers for auth tests - runs WITHOUT testMode so authentication is enforced. */
object AuthTestServers {

  @volatile private var backendFiber: Option[cats.effect.FiberIO[Nothing]] = None
  @volatile private var frontendProcess: Option[scala.sys.process.Process] = None
  @volatile private var _backendPort: Int                                  = 0
  @volatile private var _frontendPort: Int                                 = 0
  @volatile private var dbPath: Option[Path]                               = None
  @volatile private var jdbcUrl: String                                    = ""

  def backendPort: Int    = _backendPort
  def frontendPort: Int   = _frontendPort
  def frontendUrl: String = s"http://127.0.0.1:$_frontendPort"

  private def findAvailablePort(): Int = {
    Using(new ServerSocket(0)) { socket =>
      socket.setReuseAddress(true)
      socket.getLocalPort
    }.get
  }

  def startAll(): Unit = {
    if backendFiber.isDefined then {
      println("[AuthE2E] Servers already running")
      return
    }

    _backendPort = findAvailablePort()
    _frontendPort = findAvailablePort()

    println(s"[AuthE2E] Starting backend on port $_backendPort (auth ENABLED)")
    println(s"[AuthE2E] Starting frontend on port $_frontendPort")

    startBackend()
    startFrontend()

    waitForServer(s"http://127.0.0.1:$_backendPort/api/health", "Backend")
    waitForServer(s"http://127.0.0.1:$_frontendPort", "Frontend")

    println("[AuthE2E] All servers ready")
  }

  private def startBackend(): Unit = {
    val tempDb    = Files.createTempFile("ssbudget-auth-e2e-", ".db")
    dbPath = Some(tempDb)
    jdbcUrl = s"jdbc:sqlite:${tempDb.toAbsolutePath}"
    val port      = Port.fromInt(_backendPort).get
    val dbPathStr = tempDb.toAbsolutePath.toString

    // NOTE: testMode = false - authentication is ENABLED
    val serverIO: IO[Nothing] = Database.migrateAndTransactor(jdbcUrl).use { xa =>
      val repos = Repositories.fromTransactor(xa)
      ServerBuilder.build(repos, xa, port, testMode = false, dbPath = dbPathStr).useForever
    }

    backendFiber = Some(serverIO.start.unsafeRunSync())
  }

  private def startFrontend(): Unit = {
    val e2eDir      = new File(System.getProperty("user.dir"))
    val projectRoot = e2eDir.getParentFile
    val frontendDir = new File(projectRoot, "frontend")

    println(s"[AuthE2E] Frontend dir = ${frontendDir.getAbsolutePath}")

    if !frontendDir.exists() then {
      throw new RuntimeException(s"Frontend directory not found: ${frontendDir.getAbsolutePath}")
    }

    val viteConfig = new File(frontendDir, "vite.config.e2e.mjs")
    if !viteConfig.exists() then {
      throw new RuntimeException(s"Vite config not found: ${viteConfig.getAbsolutePath}")
    }

    val env = Seq(
      "VITE_PORT"    -> _frontendPort.toString,
      "VITE_API_URL" -> s"http://localhost:$_backendPort",
    )

    val cmd = Seq("npx", "vite", "--config", "vite.config.e2e.mjs")
    println(s"[AuthE2E] Running: ${cmd.mkString(" ")} in ${frontendDir.getAbsolutePath}")

    val pb = Process(cmd, frontendDir, env*)

    val logger = ProcessLogger(
      out => println(s"[vite] $out"),
      err => println(s"[vite-err] $err"),
    )

    frontendProcess = Some(pb.run(logger))
  }

  private def waitForServer(url: String, name: String, maxAttempts: Int = 60): Unit = {
    var attempts = 0
    var ready    = false

    while !ready && attempts < maxAttempts do {
      val result = Try {
        val connection = new URL(url).openConnection().asInstanceOf[HttpURLConnection]
        connection.setConnectTimeout(2000)
        connection.setReadTimeout(2000)
        connection.setRequestMethod("GET")
        try {
          connection.connect()
          val code = connection.getResponseCode
          (code, code >= 200 && code < 500)
        } finally {
          connection.disconnect()
        }
      }

      result match {
        case scala.util.Success((code, isReady)) =>
          if isReady then {
            ready = true
          } else {
            if attempts % 10 == 0 then println(s"[AuthE2E] $name returned $code, retrying...")
            attempts += 1
            Thread.sleep(1000)
          }
        case scala.util.Failure(ex)              =>
          if attempts % 10 == 0 then println(s"[AuthE2E] $name connection failed: ${ex.getMessage}, retrying...")
          attempts += 1
          Thread.sleep(1000)
      }
    }

    if !ready then {
      throw new RuntimeException(s"$name failed to start at $url after $maxAttempts seconds")
    }

    println(s"[AuthE2E] $name is ready at $url")
  }

  /** Reset the database by deleting and recreating it. This restarts the backend. */
  def resetDatabase(): Unit = {
    // Stop backend
    backendFiber.foreach { fiber =>
      fiber.cancel.unsafeRunSync()
    }
    backendFiber = None

    // Delete old database
    dbPath.foreach { path =>
      Try(Files.deleteIfExists(path))
    }

    // Create new database and restart backend
    val tempDb    = Files.createTempFile("ssbudget-auth-e2e-", ".db")
    dbPath = Some(tempDb)
    jdbcUrl = s"jdbc:sqlite:${tempDb.toAbsolutePath}"
    val port      = Port.fromInt(_backendPort).get
    val dbPathStr = tempDb.toAbsolutePath.toString

    val serverIO: IO[Nothing] = Database.migrateAndTransactor(jdbcUrl).use { xa =>
      val repos = Repositories.fromTransactor(xa)
      ServerBuilder.build(repos, xa, port, testMode = false, dbPath = dbPathStr).useForever
    }

    backendFiber = Some(serverIO.start.unsafeRunSync())

    // Wait for backend to be ready again
    waitForServer(s"http://127.0.0.1:$_backendPort/api/health", "Backend (reset)")
  }

  def stopAll(): Unit = {
    println("[AuthE2E] Stopping servers...")

    frontendProcess.foreach { p =>
      p.destroy()
      Thread.sleep(200)
    }
    frontendProcess = None

    backendFiber.foreach { fiber =>
      fiber.cancel.unsafeRunSync()
    }
    backendFiber = None

    dbPath.foreach { path =>
      Try(Files.deleteIfExists(path))
    }
    dbPath = None

    _backendPort = 0
    _frontendPort = 0

    println("[AuthE2E] Servers stopped")
  }
}
