import sbt._

object Dependencies {

  val vZio        = "1.0.3"
  val vZioLogging = "0.5.4"
  val vZioNio     = "1.0.0-RC9"

  val vLogback = "1.2.3"

  val zioCore     = "dev.zio" %% "zio"               % vZio
  val zioLog      = "dev.zio" %% "zio-logging"       % vZioLogging
  val zioLogSlf4j = "dev.zio" %% "zio-logging-slf4j" % vZioLogging
  val zioNio      = "dev.zio" %% "zio-nio"           % vZioNio

  val zioTest    = "dev.zio" %% "zio-test"     % vZio
  val zioTestSbt = "dev.zio" %% "zio-test-sbt" % vZio

  val logbackClassic = "ch.qos.logback" % "logback-classic" % vLogback
  val logbackCore    = "ch.qos.logback" % "logback-core"    % vLogback

  val uzHttp = "org.polynote" %% "uzhttp" % "0.2.6"
}
