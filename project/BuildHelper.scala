import sbt._
import sbt.Keys._
import sbtbuildinfo._
import BuildInfoKeys._
import scalafix.sbt.ScalafixPlugin.autoImport._

object BuildHelper {
  private val Scala213 = "2.13.6"

  private val ScalaDefault = Scala213

  private val stdOptions = Seq(
    "-encoding",
    "UTF-8",
    "-explaintypes",
    "-Yrangepos",
    "-feature",
    "-language:higherKinds",
    "-language:existentials",
    "-Xlint:_,-type-parameter-shadow",
    "-Xsource:2.13",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard",
    "-unchecked",
    "-deprecation",
    "-Xfatal-warnings"
  )

  private val stdOpts213 = Seq(
    "-Wunused:imports",
    "-Wunused:patvars",
    "-Wunused:privates",
    "-Wunused:params",
    "-Wunused:nowarn",
    "-Wvalue-discard"
  )

  private val stdOptsUpto212 = Seq(
    "-Xfuture",
    "-Ypartial-unification",
    "-Ywarn-nullary-override",
    "-Yno-adapted-args",
    "-Ywarn-infer-any",
    "-Ywarn-inaccessible",
    "-Ywarn-nullary-unit",
    "-Ywarn-unused-import"
  )

  private def silencerVersion = "1.7.3"

  private def extraOptions(scalaVersion: String) =
    CrossVersion.partialVersion(scalaVersion) match {
      case Some((2, 13)) =>
        stdOpts213
      case Some((2, 12)) =>
        Seq(
          "-opt-warnings",
          "-Ywarn-extra-implicit",
          "-Ywarn-unused:_,imports",
          "-Ywarn-unused:imports",
          "-opt:l:inline",
          "-opt-inline-from:<source>"
        ) ++ stdOptsUpto212
      case _             =>
        Seq("-Xexperimental") ++ stdOptsUpto212
    }

  def buildInfoSettings(packageName: String) =
    Seq(
      buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion, isSnapshot),
      buildInfoPackage := packageName,
      buildInfoObject := "BuildInfo"
    )

  def stdSettings(prjName: String) =
    Seq(
      name := s"$prjName",
      crossScalaVersions := Seq(Scala213),
      ThisBuild / scalaVersion := ScalaDefault,
      scalacOptions := stdOptions ++ extraOptions(scalaVersion.value),
      publish / skip := false,
      testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
      libraryDependencies ++=
        Seq(
          compilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3")
        ) ++ extraDeps(scalaVersion.value),
      incOptions ~= (_.withLogRecompileOnMacro(false)),
      Test / fork := true,
      Test / parallelExecution := false
    )

  private def extraDeps(scVersion: String) =
    CrossVersion.partialVersion(scVersion) match {
      case Some((2, 12)) =>
        Seq(
          ("com.github.ghik"   % "silencer-lib"    % silencerVersion % Provided)
            .cross(CrossVersion.full),
          compilerPlugin(
            ("com.github.ghik" % "silencer-plugin" % silencerVersion).cross(CrossVersion.full)
          )
        )
      case _             => Seq.empty
    }
}
