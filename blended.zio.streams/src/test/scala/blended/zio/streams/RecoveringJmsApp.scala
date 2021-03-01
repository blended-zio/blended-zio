package blended.zio.streams

import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat

import zio._
import zio.console._
import zio.clock._
import zio.duration._
import zio.logging.slf4j._

import org.apache.activemq.broker.BrokerService
import org.apache.activemq.ActiveMQConnectionFactory
import zio.stream.ZStream

import blended.zio.activemq.AMQBroker

import blended.zio.streams.jms._

import JmsDestination._
import JmsApi._
import JmsApiObject._

object RecoveringJmsApp extends App {

  private val sdf    = new SimpleDateFormat("yyyy-MM-dd-HH.mm.ss.SSS")
  private val logEnv = ZEnv.live ++ Slf4jLogger.make((_, message) => message)

  private val brokerEnv = logEnv >>> AMQBroker.simple("simple")

  private val combinedEnv =
    brokerEnv ++ defaultJmsEnv(logEnv)

  // doctag<stream>
  private val stream = ZStream
    .fromSchedule(Schedule.spaced(1000.millis).jittered)
    .mapM(_ =>
      currentTime(TimeUnit.MILLISECONDS)
        .map(sdf.format)
    )
  // end:doctag<stream>

  private val amqCF: JmsConnectionFactory =
    JmsConnectionFactory(
      "amq:amq",
      new ActiveMQConnectionFactory("vm://simple?create=false"),
      3.seconds,
      None
    )

  private val clientId: String         = "sampleApp"
  private val testDest: JmsDestination = JmsQueue("sample")

  // doctag<program>
  private val program = for {
    _         <- putStrLn("Starting JMS Broker") *> ZIO.service[BrokerService]
    mgr       <- ZIO.service[JmsConnectionManager.Service]
    f         <- ZIO.unit.schedule(Schedule.duration(30.seconds)).fork
    _         <- mgr
                   .reconnect(
                     amqCF.id + "-" + clientId,
                     Some(new Exception("Boom"))
                   )
                   .schedule(Schedule.duration(10.seconds))
                   .fork
    jmsStream <- recoveringJmsStream(amqCF, clientId, testDest, 2.seconds)
    jmsSink   <- recoveringJmsSink(amqCF, clientId, testDest, 1.second)
    consumer  <- jmsStream.foreach(s => putStrLn(s)).fork
    producer  <- stream.run(jmsSink).fork
    _         <- f.join >>> consumer.interrupt >>> producer.interrupt
  } yield ()

  // end:doctag<program>

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = program
    .provideCustomLayer(combinedEnv)
    .catchAllCause(c => putStrLn(c.prettyPrint))
    .exitCode

}
