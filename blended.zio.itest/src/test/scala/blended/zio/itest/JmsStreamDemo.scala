package blended.zio.itest

import java.text.SimpleDateFormat
import java.util.concurrent._

import zio._
import zio.clock._
import zio.console._
import zio.duration._
import zio.logging._
import zio.stream._

import zio.blocking.Blocking
import blended.zio.activemq.AMQBroker
import blended.zio.streams._
import blended.zio.streams.jms.JmsApi
import blended.zio.streams.jms.JmsApi._
import blended.zio.streams.jms.JmsConnectionManager
import blended.zio.streams.jms.JmsConnectionManager._
import blended.zio.streams.jms.JmsApiObject._
import blended.zio.streams.jms.JmsDestination._
import blended.zio.streams.jms._

import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.broker.BrokerService
import blended.zio.core.RuntimeId

object JmsStreamDemo extends App {

  private val sdf = new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss:SSS")

  val logEnv: ZLayer[ZEnv, Nothing, Logging] = ZLayer.requires[Clock with Console] >>> Logging.console(
    logLevel = LogLevel.Debug,
    format = LogFormat.ColoredLogFormat()
  )

  val brokerEnv: ZLayer[ZEnv, Throwable, Has[BrokerService]] =
    (ZLayer.requires[Blocking] ++ logEnv) >>> AMQBroker.simple("simple")

  val jmsApiEnv: ZLayer[ZEnv, Nothing, Has[JmsApiSvc]] =
    (logEnv ++ RuntimeId.default) >>> JmsApi.default

  val jmsConnMgr: ZLayer[ZEnv, Nothing, Has[JmsConnectionManagerSvc]] =
    (ZLayer.requires[Clock] ++ logEnv ++ jmsApiEnv) >>> JmsConnectionManager.live

  val combinedEnv: ZLayer[ZEnv, Nothing, Logging with Has[JmsApiSvc] with Has[BrokerService] with Has[
    JmsConnectionManagerSvc
  ]] =
    (ZLayer.requires[ZEnv] >>> (logEnv ++ brokerEnv ++ jmsApiEnv ++ jmsConnMgr)).orDie

  private val testDest: JmsDestination = JmsQueue("sample")

  private val cf: JmsConnectionFactory =
    JmsConnectionFactory(
      id = "amq:amq",
      factory = new ActiveMQConnectionFactory("vm://simple?create=false"),
      reconnectInterval = 5.seconds
    )

  private val stream = ZStream
    .fromSchedule(Schedule.spaced(100.millis).jittered)
    .mapM(_ =>
      currentTime(TimeUnit.MILLISECONDS)
        .map(sdf.format)
        .map(s => FlowEnvelope.make(s))
    )
    .map(_.mapContent(JmsMessageBody.Text(_)))

  private val program =
    for {
      _ <- ZIO.service[BrokerService]
      _ <- log.info("ActiveMQ Broker started")
      _ <- JmsEndpoint.make(cf, "endpoint", testDest).use { ep =>
             for {
               // This will send all the messages it receives to the JMS test destination
               sink     <- ep.sink
               // This is a consumer on the JMS test destination providing a stream of Flow Envelopes
               epStream <- ep.stream
               // Kick off producing the test messages
               _        <- stream.run(sink).timeout(30.seconds).fork
               // Start consuming messages // We must call ackOrDeny to clear up the inflight buffer
               _        <- epStream.foreach(env => putStrLn(env.toString()) *> env.ackOrDeny).fork
             } yield ()
           }
    } yield ()

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = program
    .provideSomeLayer[ZEnv](combinedEnv)
    .catchAllCause(c => putStrLn(c.prettyPrint))
    .exitCode
}
