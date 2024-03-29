import sbtcrossproject.Platform
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

val Scala213 = "2.13.5"

ThisBuild / crossScalaVersions := Seq("3.1.0")
ThisBuild / scalaVersion := crossScalaVersions.value.last

ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(List("test")),
)


val catsV = "2.7.0"
val catsEffectV = "3.3.0"
val fs2V = "3.2.2"
val http4sV = "0.23.6"

val munitCatsEffectV = "1.0.6"


// Projects
lazy val `h2` = project.in(file("."))
  .disablePlugins(MimaPlugin)
  .enablePlugins(NoPublishPlugin)
  .aggregate(core, examples, hpack)

// lazy val core = crossProject(JSPlatform, JVMPlatform)
//   .crossType(CrossType.Full)
lazy val core = project
  .in(file("core"))
  .settings(commonSettings)
  .settings(
    name := "h2"
  )

lazy val hpack = project.in(file("hpack"))
  .enablePlugins(NoPublishPlugin)
  .settings(commonSettings)

lazy val examples = project.in(file("examples"))
  .enablePlugins(NoPublishPlugin)
  .settings(commonSettings)
  .settings(
    mainClass in reStart := Some("ServerMain")
  )
  .dependsOn(core)

lazy val site = project.in(file("site"))
  .disablePlugins(MimaPlugin)
  .enablePlugins(MicrositesPlugin)
  .enablePlugins(MdocPlugin)
  .enablePlugins(NoPublishPlugin)
  .settings(commonSettings)
  .dependsOn(core)
  .settings{
    import microsites._
    Seq(
      micrositeName := "h2",
      micrositeDescription := "H2 Playground Impl",
      micrositeAuthor := "Christopher Davenport",
      micrositeGithubOwner := "ChristopherDavenport",
      micrositeGithubRepo := "h2",
      micrositeBaseUrl := "/h2",
      micrositeDocumentationUrl := "https://www.javadoc.io/doc/org.http4s/h2_2.13",
      micrositeGitterChannelUrl := "ChristopherDavenport/libraries", // Feel Free to Set To Something Else
      micrositeFooterText := None,
      micrositeHighlightTheme := "atom-one-light",
      micrositePalette := Map(
        "brand-primary" -> "#3e5b95",
        "brand-secondary" -> "#294066",
        "brand-tertiary" -> "#2d5799",
        "gray-dark" -> "#49494B",
        "gray" -> "#7B7B7E",
        "gray-light" -> "#E5E5E6",
        "gray-lighter" -> "#F4F3F4",
        "white-color" -> "#FFFFFF"
      ),
      micrositePushSiteWith := GitHub4s,
      micrositeGithubToken := sys.env.get("GITHUB_TOKEN"),
      micrositeExtraMdFiles := Map(
          file("CODE_OF_CONDUCT.md")  -> ExtraMdFileConfig("code-of-conduct.md",   "page", Map("title" -> "code of conduct",   "section" -> "code of conduct",   "position" -> "100")),
          file("LICENSE")             -> ExtraMdFileConfig("license.md",   "page", Map("title" -> "license",   "section" -> "license",   "position" -> "101"))
      )
    )
  }

// General Settings
lazy val commonSettings = Seq(
  testFrameworks += new TestFramework("munit.Framework"),

  libraryDependencies ++= Seq(
    "org.typelevel"               %% "cats-core"                  % catsV,
    "org.typelevel"               %% "cats-effect"                % catsEffectV,

    "co.fs2"                      %% "fs2-core"                   % fs2V,
    "co.fs2"                      %% "fs2-io"                     % fs2V,

    "org.http4s"                  %% "http4s-dsl"                 % http4sV,
    "org.http4s"                  %% "http4s-ember-server"        % http4sV,
    "org.http4s"                  %% "http4s-ember-client"        % http4sV,
    "com.twitter"                 % "hpack"                       % "1.0.2",

    "org.typelevel"               %%% "munit-cats-effect-3"        % munitCatsEffectV         % Test,

    // "org.tpolecat"                %% "doobie-specs2"              % doobieV       % Test,

    // "io.chrisdavenport"           %% "log4cats-core"              % log4catsV,
    // "io.chrisdavenport"           %% "log4cats-slf4j"             % log4catsV,
    // "io.chrisdavenport"           %% "log4cats-testing"           % log4catsV     % Test,

    // "org.specs2"                  %% "specs2-core"                % specs2V       % Test,
    // "org.specs2"                  %% "specs2-scalacheck"          % specs2V       % Test
  )
)

// General Settings
inThisBuild(List(
  organization := "org.http4s",
  developers := List(
    Developer("ChristopherDavenport", "Christopher Davenport", "chris@christopherdavenport.tech", url("https://github.com/ChristopherDavenport"))
  ),

  homepage := Some(url("https://github.com/ChristopherDavenport/h2")),
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),

  pomIncludeRepository := { _ => false},
  scalacOptions in (Compile, doc) ++= Seq(
      "-groups",
      "-sourcepath", (baseDirectory in LocalRootProject).value.getAbsolutePath,
      "-doc-source-url", "https://github.com/ChristopherDavenport/h2/blob/v" + version.value + "€{FILE_PATH}.scala"
  )
))
