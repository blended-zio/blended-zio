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
    ),
    semanticdbEnabled := true,
    semanticdbOptions += "-P:semanticdb:synthetics:on",
    semanticdbVersion := scalafixSemanticdb.revision,
    scalafixScalaBinaryVersion := CrossVersion.binaryScalaVersion(scalaVersion.value),
    scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.6.0"
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
      blendedJolokia,
      blendedStreams,
      blendedSolace,
      blendedITest,
      blendedITestContainer,
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
      libraryDependencies ++= Seq(zioConfig, argonaut)
    )

lazy val blendedJmx =
  (project in file("blended.zio.jmx"))
    .settings(
      stdSettings("blended.zio.jmx")
    )
    .settings(
      libraryDependencies ++= (zioDefault ++ testDefault)
    )

lazy val blendedJolokia =
  (project in file("blended.zio.jolokia"))
    .settings(
      stdSettings("blended.zio.jolokia")
    )
    .settings(
      libraryDependencies ++= (zioDefault ++ testDefault) ++ Seq(
        argonaut,
        sttp3Core,
        sttp3Backend,
        jolokiaAgent % Runtime
      ),
      Test / Keys.javaOptions += {
        val jarFile = (Test / Keys.dependencyClasspathAsJars).value
          .map(_.data)
          .find { f =>
            f.getName().startsWith("jolokia-jvm-")
          }
          .get
        s"-javaagent:$jarFile=port=0,host=localhost"
      }
    )
    .dependsOn(blendedCore)

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
      libraryDependencies ++= zioDefault ++ Seq(
        zioTest,
        zioTestSbt,
        logbackCore    % Test,
        logbackClassic % Test,
        zioLogSlf4j    % Test
      )
    )
    .dependsOn(blendedCore, blendedStreams, blendedSolace)

lazy val blendedITestContainer =
  (project in file("blended.zio.itest.container"))
    .settings(stdSettings("blended.zio.itest.container"))
    .settings(
      libraryDependencies ++= zioDefault ++ Seq(
        testContainers
      )
    )
    .dependsOn(blendedITest)

lazy val docs = project
  .in(file("website"))
  .settings(
    publish / skip := true,
    moduleName := "blended.zio.website",
    scalacOptions -= "-Yno-imports",
    libraryDependencies ++= Seq(
      zioCore,
      uzHttp
    ),
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(
      blendedCore,
      blendedJmx,
      blendedJolokia,
      blendedStreams,
      blendedSolace,
      blendedActiveMq,
      blendedITest,
      blendedITestContainer
    ),
    ScalaUnidoc / unidoc / target := (LocalRootProject / baseDirectory).value / "website" / "static" / "api",
    mdocIn := (LocalRootProject / baseDirectory).value / "docs",
    mdocOut := (LocalRootProject / baseDirectory).value / "website" / "docs",
    cleanFiles += (ScalaUnidoc / unidoc / target).value,
    docusaurusCreateSite := docusaurusCreateSite.dependsOn(Compile / unidoc).value,
    docusaurusPublishGhpages := docusaurusPublishGhpages.dependsOn(Compile / unidoc).value
  )
  .dependsOn(blendedCore, blendedJmx, blendedStreams, blendedActiveMq)
  .enablePlugins(MdocPlugin, DocusaurusPlugin, ScalaUnidocPlugin)
