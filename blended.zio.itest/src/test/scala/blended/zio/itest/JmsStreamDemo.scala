package blended.zio.itest

import zio._
import zio.clock._
import zio.console._
import zio.duration._
import zio.logging._
import zio.logging.slf4j._
import zio.stream._

import org.apache.activemq.broker.BrokerService
import org.apache.activemq.ActiveMQConnectionFactory

import blended.zio.activemq.AMQBroker

import blended.zio.streams._
import blended.zio.streams.jms._
import JmsApi._
import JmsApiObject._
import JmsDestination._
import java.util.concurrent._
import java.text.SimpleDateFormat

object JmsStreamDemo extends App {

  private val sdf = new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss:SSS")

  private val logEnv = ZEnv.live ++ Slf4jLogger.make((_, message) => message)
  // private val logEnv = ZEnv.live ++ Logging.console(
  //   logLevel = LogLevel.Trace,
  //   format = LogFormat.ColoredLogFormat()
  // )

  private val brokerEnv = logEnv >>> AMQBroker.simple("simple")

  private val combinedEnv = logEnv ++ brokerEnv ++ defaultJmsEnv(logEnv)

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
               f        <- ZIO.unit.schedule(Schedule.duration(30.seconds)).fork
               sink     <- EndpointStreams.sink(ep)
               epStream <- EndpointStreams.stream(ep)
               producer <- stream.run(sink).fork
               consumer <- epStream.foreach(env => putStrLn(env.toString()) *> env.ackOrDeny).fork
               _        <- f.join *> consumer.interrupt *> producer.interrupt
             } yield ()
           }
    } yield ()

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = program
    .provideCustomLayer(combinedEnv)
    .catchAllCause(c => putStrLn(c.prettyPrint))
    .exitCode
}
