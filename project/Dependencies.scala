import sbt._

object Dependencies {

  val vAmq            = "5.16.0"
  val vJolokia        = "1.6.2"
  val vLogback        = "1.2.5"
  val vSolace         = "10.12.0"
  val vSttp3          = "3.3.13"
  val vTestContainers = "0.39.6"

  val vZio        = "1.0.14"
  val vZioConfig  = "1.0.5"
  val vZioLogging = "0.5.11"
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

  val amqBroker      = "org.apache.activemq"            % "activemq-broker"               % vAmq
  val amqKahaDb      = "org.apache.activemq"            % "activemq-kahadb-store"         % vAmq
  val argonaut       = "io.argonaut"                   %% "argonaut"                      % "6.3.6"
  val jolokiaJvm     = "org.jolokia"                    % "jolokia-jvm"                   % vJolokia
  val jolokiaAgent   = jolokiaJvm.classifier("agent")
  val jms_1_1        = "org.apache.geronimo.specs"      % "geronimo-jms_1.1_spec"         % "1.1.1"
  val logbackClassic = "ch.qos.logback"                 % "logback-classic"               % vLogback
  val logbackCore    = "ch.qos.logback"                 % "logback-core"                  % vLogback
  val solJms         = "com.solacesystems"              % "sol-jms"                       % vSolace
  val sttp3Core      = "com.softwaremill.sttp.client3" %% "core"                          % vSttp3
  val sttp3Backend   = "com.softwaremill.sttp.client3" %% "async-http-client-backend-zio" % vSttp3
  val testContainers = "com.dimafeng"                  %% "testcontainers-scala"          % vTestContainers
  val uzHttp         = "org.polynote"                  %% "uzhttp"                        % "0.2.7"

  /* --- Convenient dependency groups */

  val amqDefault  = Seq(amqBroker, jms_1_1)
  val zioDefault  = Seq(zioCore, zioLog, zioStreams)
  val testDefault = Seq(zioTest, zioTestSbt, logbackCore, logbackClassic, zioLogSlf4j).map(_ % Test)
}
