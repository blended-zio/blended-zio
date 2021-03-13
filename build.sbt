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
    .aggregate(
      blendedActiveMq,
      blendedCore,
      blendedJmx,
      blendedStreams,
      blendedSolace,
      blendedITest,
      docs
    )

lazy val blendedActiveMq =
  (project in file("blended.zio.activemq"))
    .settings(stdSettings("blended.zio.activemq"))
    .settings(
      libraryDependencies ++= (amqDefault ++ zioDefault ++ testDefault)
    )

lazy val blendedCore =
  (project in file("blended.zio.core"))
    .settings(stdSettings("blended.zio.core"))
    .settings(
      libraryDependencies ++= (zioDefault ++ testDefault),
      libraryDependencies += zioConfig
    )

lazy val blendedJmx =
  (project in file("blended.zio.jmx"))
    .settings(
      stdSettings("blended.zio.jmx")
    )
    .settings(
      libraryDependencies ++= (zioDefault ++ testDefault)
    )

lazy val blendedSolace =
  (project in file("blended.zio.solace"))
    .settings(stdSettings("blended.zio.solace"))
    .settings(
      libraryDependencies ++= (zioDefault ++ testDefault),
      libraryDependencies ++= Seq(jms_1_1, argonaut, solJms, sttp3Core, sttp3Backend)
    )
    .dependsOn(blendedCore)

lazy val blendedStreams =
  (project in file("blended.zio.streams"))
    .settings(stdSettings("blended.zio.streams"))
    .settings(
      libraryDependencies ++= (zioDefault ++ testDefault),
      libraryDependencies += jms_1_1
    )
    .dependsOn(blendedCore, blendedActiveMq)

lazy val blendedITest =
  (project in file("blended.zio.itest"))
    .settings(stdSettings("blended.zio.itest"))
    .settings(
      libraryDependencies ++= (zioDefault ++ testDefault)
    )
    .dependsOn(blendedCore, blendedStreams, blendedSolace)

lazy val docs = project
  .in(file("website"))
  .settings(
    skip.in(publish) := true,
    moduleName := "blended.zio.website",
    scalacOptions -= "-Yno-imports",
    libraryDependencies ++= Seq(
      zioCore,
      uzHttp
    ),
    unidocProjectFilter in (ScalaUnidoc, unidoc) := inProjects(
      blendedCore,
      blendedJmx,
      blendedStreams,
      blendedSolace,
      blendedActiveMq,
      blendedITest
    ),
    target in (ScalaUnidoc, unidoc) := (baseDirectory in LocalRootProject).value / "website" / "static" / "api",
    mdocIn := (baseDirectory in LocalRootProject).value / "docs",
    mdocOut := (baseDirectory in LocalRootProject).value / "website" / "docs",
    cleanFiles += (target in (ScalaUnidoc, unidoc)).value,
    docusaurusCreateSite := docusaurusCreateSite.dependsOn(unidoc in Compile).value,
    docusaurusPublishGhpages := docusaurusPublishGhpages.dependsOn(unidoc in Compile).value
  )
  .dependsOn(blendedCore, blendedJmx, blendedStreams, blendedActiveMq)
  .enablePlugins(MdocPlugin, DocusaurusPlugin, ScalaUnidocPlugin)
