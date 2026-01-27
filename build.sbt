import org.scalajs.linker.interface.ModuleKind

ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.5.2"
ThisBuild / organization := "org.ssbudget"

// Dependency versions (only for deps used in multiple modules)
val http4sVersion  = "0.23.30"
val tapirVersion   = "1.11.11"
val circeVersion   = "0.14.10"
val doobieVersion  = "1.0.0-RC6"

lazy val root = (project in file("."))
  .aggregate(shared.jvm, shared.js, backend, frontend)
  .settings(
    name         := "ssbudget",
    publish      := {},
    publishLocal := {}
  )

lazy val shared = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("shared"))
  .settings(
    name := "shared",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %%% "tapir-core" % tapirVersion,
      "io.circe"                    %%% "circe-core" % circeVersion
    )
  )
  .jsSettings(
    libraryDependencies ++= Seq(
      "io.github.cquiroz" %%% "scala-java-time" % "2.6.0"
    )
  )

lazy val backend = (project in file("backend"))
  .dependsOn(shared.jvm)
  .settings(
    name := "backend",
    libraryDependencies ++= Seq(
      "org.typelevel"               %% "cats-effect"         % "3.5.7",
      "org.http4s"                  %% "http4s-ember-server" % http4sVersion,
      "org.http4s"                  %% "http4s-dsl"          % http4sVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe"    % tapirVersion,
      "io.circe"                    %% "circe-generic"       % circeVersion,
      "ch.qos.logback"               % "logback-classic"     % "1.5.15",
      // Database
      "org.tpolecat"  %% "doobie-core"  % doobieVersion,
      "org.tpolecat"  %% "doobie-hikari" % doobieVersion,
      "org.xerial"     % "sqlite-jdbc"  % "3.47.2.0",
      "org.flywaydb"   % "flyway-core"  % "10.22.0",
      // Testing
      "org.scalatest" %% "scalatest"                     % "3.2.19"  % Test,
      "org.typelevel" %% "cats-effect-testing-scalatest" % "1.6.0"   % Test
    ),
    Compile / run / fork := true
  )

lazy val frontend = (project in file("frontend"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(shared.js)
  .settings(
    name := "frontend",
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "com.raquo"                     %%% "laminar"           % "17.2.0",
      "com.softwaremill.sttp.tapir"   %%% "tapir-sttp-client" % tapirVersion,
      "com.softwaremill.sttp.client3" %%% "core"              % "3.10.2",
      "io.circe"                      %%% "circe-generic"     % circeVersion,
      "io.circe"                      %%% "circe-parser"      % circeVersion
    )
  )
