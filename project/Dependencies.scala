import sbt._

object Dependencies {

  val vAmq        = "5.16.0"
  val vLogback    = "1.2.3"
  val vZio        = "1.0.4-2"
  val vZioConfig  = "1.0.0"
  val vZioLogging = "0.5.7"
  val vZioNio     = "1.0.0-RC9"

  // Dependencies

  val zioCore     = "dev.zio" %% "zio"               % vZio
  val zioConfig   = "dev.zio" %% "zio-config"        % vZioConfig
  val zioLog      = "dev.zio" %% "zio-logging"       % vZioLogging
  val zioLogSlf4j = "dev.zio" %% "zio-logging-slf4j" % vZioLogging
  val zioNio      = "dev.zio" %% "zio-nio"           % vZioNio
  val zioStreams  = "dev.zio" %% "zio-streams"       % vZio
  val zioTest     = "dev.zio" %% "zio-test"          % vZio
  val zioTestSbt  = "dev.zio" %% "zio-test-sbt"      % vZio

  val amqBroker      = "org.apache.activemq"       % "activemq-broker"       % vAmq
  val amqKahaDb      = "org.apache.activemq"       % "activemq-kahadb-store" % vAmq
  val jms_1_1        = "org.apache.geronimo.specs" % "geronimo-jms_1.1_spec" % "1.1.1"
  val logbackClassic = "ch.qos.logback"            % "logback-classic"       % vLogback
  val logbackCore    = "ch.qos.logback"            % "logback-core"          % vLogback
  val solJms         = "com.solacesystems"         % "sol-jms"               % "10.10.0"
  val uzHttp         = "org.polynote"             %% "uzhttp"                % "0.2.6"

  /* --- Convenient dependency groups */

  val amqDefault  = Seq(amqBroker, jms_1_1)
  val zioDefault  = Seq(zioCore, zioLog, zioStreams)
  val testDefault = Seq(zioTest, zioTestSbt, logbackCore, logbackClassic, zioLogSlf4j).map(_ % Test)
}
