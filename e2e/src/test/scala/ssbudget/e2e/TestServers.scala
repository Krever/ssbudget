package ssbudget.e2e

import cats.effect.IO
import cats.implicits.*
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.Port
import ssbudget.backend.ServerBuilder
import ssbudget.backend.db.{Database, Repositories}

import java.io.File
import java.net.{HttpURLConnection, ServerSocket, URL}
import java.nio.file.{Files, Path}
import scala.sys.process.{Process, ProcessLogger}
import scala.util.{Try, Using}

object TestServers {

  @volatile private var backendFiber: Option[cats.effect.FiberIO[Nothing]] = None
  @volatile private var frontendProcess: Option[scala.sys.process.Process] = None
  @volatile private var _backendPort: Int                                  = 0
  @volatile private var _frontendPort: Int                                 = 0
  @volatile private var dbPath: Option[Path]                               = None

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
      println("Servers already running")
      return
    }

    _backendPort = findAvailablePort()
    _frontendPort = findAvailablePort()

    println(s"[E2E] Starting backend on port $_backendPort")
    println(s"[E2E] Starting frontend on port $_frontendPort")

    startBackend()
    startFrontend()

    waitForServer(s"http://127.0.0.1:$_backendPort/api/health", "Backend")
    waitForServer(s"http://127.0.0.1:$_frontendPort", "Frontend")

    println("[E2E] All servers ready")
  }

  private def startBackend(): Unit = {
    val tempDb    = Files.createTempFile("ssbudget-e2e-", ".db")
    dbPath = Some(tempDb)
    val jdbcUrl   = s"jdbc:sqlite:${tempDb.toAbsolutePath}"
    val port      = Port.fromInt(_backendPort).get
    val dbPathStr = tempDb.toAbsolutePath.toString

    val serverIO: IO[Nothing] = Database.migrateAndTransactor(jdbcUrl).use { xa =>
      val repos = Repositories.fromTransactor(xa)
      ServerBuilder.build(repos, xa, port, testMode = true, dbPath = dbPathStr).useForever
    }

    backendFiber = Some(serverIO.start.unsafeRunSync())
  }

  private def startFrontend(): Unit = {
    // Find project root - user.dir is set to e2e directory via sbt javaOptions
    val e2eDir      = new File(System.getProperty("user.dir"))
    val projectRoot = e2eDir.getParentFile
    val frontendDir = new File(projectRoot, "frontend")

    println(s"[E2E] user.dir = ${System.getProperty("user.dir")}")
    println(s"[E2E] Frontend dir = ${frontendDir.getAbsolutePath}")

    if !frontendDir.exists() then {
      throw new RuntimeException(s"Frontend directory not found: ${frontendDir.getAbsolutePath}")
    }

    // Check if vite config exists
    val viteConfig = new File(frontendDir, "vite.config.e2e.mjs")
    if !viteConfig.exists() then {
      throw new RuntimeException(s"Vite config not found: ${viteConfig.getAbsolutePath}")
    }

    val env = Seq(
      "VITE_PORT"    -> _frontendPort.toString,
      "VITE_API_URL" -> s"http://localhost:$_backendPort",
    )

    // Use npx vite with config
    val cmd = Seq("npx", "vite", "--config", "vite.config.e2e.mjs")
    println(s"[E2E] Running: ${cmd.mkString(" ")} in ${frontendDir.getAbsolutePath}")
    println(s"[E2E] Environment: VITE_PORT=$_frontendPort, VITE_API_URL=http://localhost:$_backendPort")

    val pb = Process(cmd, frontendDir, env*)

    // Capture all output for debugging
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
            if attempts % 10 == 0 then println(s"[E2E] $name returned $code, retrying...")
            attempts += 1
            Thread.sleep(1000)
          }
        case scala.util.Failure(ex)              =>
          if attempts % 10 == 0 then println(s"[E2E] $name connection failed: ${ex.getMessage}, retrying...")
          attempts += 1
          Thread.sleep(1000)
      }
    }

    if !ready then {
      throw new RuntimeException(s"$name failed to start at $url after $maxAttempts seconds")
    }

    println(s"[E2E] $name is ready at $url")
  }

  def stopAll(): Unit = {
    println("[E2E] Stopping servers...")

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

    println("[E2E] Servers stopped")
  }
}
