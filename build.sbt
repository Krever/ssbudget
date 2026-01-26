import org.scalajs.linker.interface.ModuleKind

ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.5.2"
ThisBuild / organization := "org.ssbudget"

// Dependency versions
val catsEffectVersion = "3.5.7"
val http4sVersion     = "0.23.30"
val tapirVersion      = "1.11.11"
val circeVersion      = "0.14.10"
val laminarVersion    = "17.2.0"

lazy val root = (project in file("."))
  .aggregate(shared.jvm, shared.js, backend, frontend)
  .settings(
    name    := "ssbudget",
    publish := {},
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
  .jvmSettings(
    idePackagePrefix := Some("ssbudget.shared")
  )
  .jsSettings(
    idePackagePrefix := Some("ssbudget.shared")
  )

lazy val backend = (project in file("backend"))
  .dependsOn(shared.jvm)
  .settings(
    name := "backend",
    idePackagePrefix := Some("ssbudget.backend"),
    libraryDependencies ++= Seq(
      "org.typelevel"               %% "cats-effect"            % catsEffectVersion,
      "org.http4s"                  %% "http4s-ember-server"    % http4sVersion,
      "org.http4s"                  %% "http4s-dsl"             % http4sVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server"    % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe"       % tapirVersion,
      "io.circe"                    %% "circe-generic"          % circeVersion,
      "ch.qos.logback"               % "logback-classic"        % "1.5.15"
    ),
    Compile / run / fork := true
  )

lazy val frontend = (project in file("frontend"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(shared.js)
  .settings(
    name := "frontend",
    idePackagePrefix := Some("ssbudget.frontend"),
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "com.raquo"                      %%% "laminar"             % laminarVersion,
      "com.softwaremill.sttp.tapir"    %%% "tapir-sttp-client"   % tapirVersion,
      "com.softwaremill.sttp.client3"  %%% "core"                % "3.10.2",
      "io.circe"                       %%% "circe-generic"       % circeVersion,
      "io.circe"                       %%% "circe-parser"        % circeVersion
    )
  )
