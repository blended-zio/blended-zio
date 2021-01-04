import bloop.shaded.ch.epfl.scala.bsp4j.BuildTargetIdentifier
import sbt._
import sbt.Keys._
import BuildHelper._
import Dependencies._

inThisBuild(
  List(
    organization := "de.wayofquality",
    homepage := Some(url("https://blended-zio.github.io/")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer(
        "atooni",
        "Andreas Gies",
        "andreas@wayofquality.de",
        url("http://blended-scala.org")
      )
    ),
    pgpPassphrase := sys.env.get("PGP_PASSWORD").map(_.toArray),
    pgpPublicRing := file("/tmp/public.asc"),
    pgpSecretRing := file("/tmp/secret.asc"),
    scmInfo := Some(
      ScmInfo(url("https://github.com/blended-zio/blended-zio/"), "scm:git:git@github.com:blended-zio/blended-zio.git")
    )
  )
)

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")

testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))

resolvers += Resolver.sonatypeRepo("snapshots")

lazy val root =
  (project in file("."))
    .settings(
      stdSettings("blended.zio")
    )
    .settings(buildInfoSettings("blended.zio"))
    .enablePlugins(BuildInfoPlugin)
    .aggregate(blendedJmx, docs)

lazy val blendedJmx =
  (project in file("blended.zio.jmx"))
    .settings(
      stdSettings("blended.zio.jmx")
    )
    .settings(
      libraryDependencies ++= Seq(
        zioCore,
        zioLog,
        logbackClassic % Test,
        logbackCore    % Test,
        zioLogSlf4j    % Test,
        zioTest        % Test,
        zioTestSbt     % Test
      )
    )

lazy val docs = project
  .in(file("blended-zio-docs"))
  .settings(
    skip.in(publish) := true,
    moduleName := "blended-zio.blended-zio-docs",
    scalacOptions -= "-Yno-imports",
    libraryDependencies ++= Seq(
      zioCore,
      uzHttp
    ),
    unidocProjectFilter in (ScalaUnidoc, unidoc) := inProjects(blendedJmx),
    target in (ScalaUnidoc, unidoc) := (baseDirectory in LocalRootProject).value / "website" / "static" / "api",
    cleanFiles += (target in (ScalaUnidoc, unidoc)).value,
    docusaurusCreateSite := docusaurusCreateSite.dependsOn(unidoc in Compile).value,
    docusaurusPublishGhpages := docusaurusPublishGhpages.dependsOn(unidoc in Compile).value
  )
  .dependsOn(blendedJmx)
  .enablePlugins(MdocPlugin, DocusaurusPlugin, ScalaUnidocPlugin)
