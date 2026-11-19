import org.scalajs.linker.interface.ModuleKind

ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.5.2"
ThisBuild / organization := "org.ssbudget"

// Dependency versions (only for deps used in multiple modules)
val http4sVersion = "0.23.30"
val tapirVersion  = "1.11.11"
val circeVersion  = "0.14.10"
val doobieVersion = "1.0.0-RC6"
val sttpVersion   = "3.10.2"

// The SQLite driver unpacks its native library to a NEW temporary file in each JVM. macOS checks a new library file before it loads it, and on
// a busy machine that check takes minutes (measured on 2026-11-19: about 200 s for the first connection, against about 30 s with a stable
// file; under a second on a quiet machine). So the JVMs that sbt starts for tests and for `run` use one stable copy for each driver version,
// unpacked into the target directory. A platform that is not listed here keeps the default behaviour of the driver.
lazy val sqliteNative = taskKey[Seq[String]]("JVM options that point the SQLite driver at a stable copy of its native library")

def sqliteNativeOptions(classpath: Seq[File], target: File): Seq[String] = {
  val os       = sys.props.getOrElse("os.name", "").toLowerCase
  val arch     = sys.props.getOrElse("os.arch", "").toLowerCase match {
    case "aarch64" | "arm64" => Some("aarch64")
    case "x86_64" | "amd64"  => Some("x86_64")
    case _                   => None
  }
  val platform =
    if (os.contains("mac")) arch.map(a => (s"Mac/$a", "libsqlitejdbc.dylib"))
    else if (os.contains("linux")) arch.map(a => (s"Linux/$a", "libsqlitejdbc.so"))
    else None
  val options  = for {
    (dir, lib) <- platform
    jar        <- classpath.find(_.getName.startsWith("sqlite-jdbc"))
  } yield {
    val out  = target / "sqlite-native" / jar.getName.stripSuffix(".jar")
    val file = out / lib
    if (!file.exists()) {
      IO.unzip(jar, out / "unpacked", new SimpleFilter(_ == s"org/sqlite/native/$dir/$lib"))
      IO.copyFile(out / "unpacked" / "org" / "sqlite" / "native" / dir / lib, file)
    }
    Seq(s"-Dorg.sqlite.lib.path=${out.getAbsolutePath}", s"-Dorg.sqlite.lib.name=$lib")
  }
  options.getOrElse(Nil)
}

// The compiler is as strict as sbt-tpolecat makes it, and a warning fails the build in CI (the plugin reads the `CI` variable; locally a
// warning is only shown). One exception, for test code: a ScalaTest test is a list of assertions, and each assertion is a value that is not
// used. The two options that report such values would report every test, so tests compile without them.
import org.typelevel.scalacoptions.ScalacOptions
val testCodeOptions = Seq(
  Test / tpolecatExcludeOptions ++= Set(ScalacOptions.warnNonUnitStatement, ScalacOptions.warnValueDiscard),
)

lazy val root = (project in file("."))
  .aggregate(shared.jvm, shared.js, backend, frontend, e2e)
  .settings(
    name         := "ssbudget",
    publish      := {},
    publishLocal := {},
  )

lazy val e2e = (project in file("e2e"))
  .dependsOn(backend % "test->test;test->compile")
  .settings(
    name         := "e2e",
    libraryDependencies ++= Seq(
      "org.scalatest"          %% "scalatest"        % "3.2.19" % Test,
      "org.seleniumhq.selenium" % "selenium-java"    % "4.27.0" % Test,
      "io.github.bonigarcia"    % "webdrivermanager" % "5.9.2"  % Test,
    ),
    testCodeOptions,
    Test / fork  := true,
    Test / javaOptions ++= Seq(
      s"-Duser.dir=${baseDirectory.value.getAbsolutePath}",
    ),
    sqliteNative := sqliteNativeOptions((Test / dependencyClasspath).value.map(_.data), target.value),
    Test / javaOptions ++= sqliteNative.value,
    // Show how long each test and suite takes: the browser tests are the slow part of a check, and this says where the time goes.
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oD"),
  )

lazy val shared = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("shared"))
  .settings(
    name := "shared",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %%% "tapir-core"       % tapirVersion,
      "com.softwaremill.sttp.tapir" %%% "tapir-json-circe" % tapirVersion,
      "io.circe"                    %%% "circe-core"       % circeVersion,
    ),
  )
  .jsSettings(
    libraryDependencies ++= Seq(
      "io.github.cquiroz" %%% "scala-java-time" % "2.6.0",
    ),
  )

lazy val backend = (project in file("backend"))
  .enablePlugins(JavaAppPackaging)
  .dependsOn(shared.jvm)
  .settings(
    name                 := "backend",
    Compile / mainClass  := Some("ssbudget.backend.Main"),
    executableScriptName := "ssbudget",
    libraryDependencies ++= Seq(
      "org.typelevel"                 %% "cats-effect"                   % "3.5.7",
      "org.http4s"                    %% "http4s-ember-server"           % http4sVersion,
      "org.http4s"                    %% "http4s-dsl"                    % http4sVersion,
      "org.http4s"                    %% "http4s-circe"                  % http4sVersion,
      "com.softwaremill.sttp.tapir"   %% "tapir-http4s-server"           % tapirVersion,
      "com.softwaremill.sttp.tapir"   %% "tapir-json-circe"              % tapirVersion,
      "com.softwaremill.sttp.client3" %% "fs2"                           % sttpVersion,
      "io.circe"                      %% "circe-generic"                 % circeVersion,
      "ch.qos.logback"                 % "logback-classic"               % "1.5.15",
      // Database
      "org.tpolecat"                  %% "doobie-core"                   % doobieVersion,
      "org.tpolecat"                  %% "doobie-hikari"                 % doobieVersion,
      "org.xerial"                     % "sqlite-jdbc"                   % "3.47.2.0",
      "org.flywaydb"                   % "flyway-core"                   % "10.22.0",
      // Authentication
      "de.mkammerer"                   % "argon2-jvm"                    % "2.11",
      "com.yubico"                     % "webauthn-server-core"          % "2.5.3",
      // Testing
      "org.scalatest"                 %% "scalatest"                     % "3.2.19" % Test,
      "org.typelevel"                 %% "cats-effect-testing-scalatest" % "1.6.0"  % Test,
      "org.wiremock"                   % "wiremock-standalone"           % "3.9.1"  % Test,
    ),
    testCodeOptions,
    Compile / run / fork := true,
    sqliteNative         := sqliteNativeOptions((Test / dependencyClasspath).value.map(_.data), target.value),
    Compile / run / javaOptions ++= sqliteNative.value,
    // The backend tests run inside the JVM of sbt, so the same two settings go in as system properties, before the first test opens a database.
    Test / testOptions += Tests.Setup { () =>
      sqliteNative.value.foreach { option =>
        val Array(key, value) = option.stripPrefix("-D").split("=", 2)
        if (sys.props.get(key).isEmpty) sys.props(key) = value
      }
    },
  )

lazy val frontend = (project in file("frontend"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(shared.js)
  .settings(
    name                            := "frontend",
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "com.raquo"                     %%% "laminar"           % "17.2.0",
      "com.raquo"                     %%% "waypoint"          % "10.0.0-M1",
      "com.softwaremill.sttp.tapir"   %%% "tapir-sttp-client" % tapirVersion,
      "com.softwaremill.sttp.client3" %%% "core"              % sttpVersion,
      "io.circe"                      %%% "circe-generic"     % circeVersion,
      "io.circe"                      %%% "circe-parser"      % circeVersion,
    ),
  )
