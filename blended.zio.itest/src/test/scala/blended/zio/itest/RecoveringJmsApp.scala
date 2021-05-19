package blended.zio.itest

import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit

import zio._
import zio.clock._
import zio.console._
import zio.duration._
import zio.logging.slf4j._
import zio.stream.ZStream

import blended.zio.activemq.AMQBroker
import blended.zio.streams._
import blended.zio.streams.jms._

import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.broker.BrokerService

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
        .map(s => FlowEnvelope.make(s))
    )
  // end:doctag<stream>

  private val amqCF: JmsConnectionFactory =
    JmsConnectionFactory(
      id = "amq:amq",
      factory = new ActiveMQConnectionFactory("vm://simple?create=false"),
      reconnectInterval = 3.seconds
    )

  private val clientId: String         = "recovery"
  private val testDest: JmsDestination = JmsQueue("sample")

  // doctag<program>
  private val program = for {
    _         <- putStrLn("Starting JMS Broker") *> ZIO.service[BrokerService]
    mgr       <- ZIO.service[JmsConnectionManager.Service]
    con       <- mgr.connect(amqCF, clientId)
    f         <- ZIO.unit.schedule(Schedule.duration(30.seconds)).fork
    _         <- mgr
                   .reconnect(
                     con,
                     Some(new Exception("Boom"))
                   )
                   .schedule(Schedule.duration(10.seconds))
                   .fork
    jmsStream <- recoveringJmsStream(amqCF, clientId, testDest, 2.seconds)
    jmsSink   <- recoveringJmsSink(amqCF, clientId, testDest, 1.second, stringEnvelopeEncoder)
    consumer  <- jmsStream.foreach(s => putStrLn(s.toString())).fork
    producer  <- stream.run(jmsSink).fork
    _         <- f.join >>> consumer.interrupt >>> producer.interrupt
  } yield ()

  // end:doctag<program>

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = program
    .provideCustomLayer(combinedEnv)
    .catchAllCause(c => putStrLn(c.prettyPrint))
    .exitCode

}
