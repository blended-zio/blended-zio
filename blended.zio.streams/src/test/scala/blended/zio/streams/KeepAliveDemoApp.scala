package blended.zio.streams

import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat

import zio._
import zio.console._
import zio.clock._
import zio.duration._
import zio.logging.slf4j._

import blended.zio.activemq.AMQBroker
import org.apache.activemq.broker.BrokerService
import blended.zio.streams.jms._
import org.apache.activemq.ActiveMQConnectionFactory
import zio.stream.ZStream

object KeepAliveDemoApp extends App {

  private val sdf: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH.mm.ss.SSS")

  private val logEnv    = ZEnv.live ++ Slf4jLogger.make((_, message) => message)
  private val brokerEnv = logEnv >>> AMQBroker.simple("simple")
  private val mgrEnv    = ZIOJmsConnectionManager.Service.make

  private val combinedEnv = logEnv ++ brokerEnv ++ mgrEnv

  // doctag<stream>
  private val stream: ZStream[ZEnv, Nothing, String] = ZStream
    .fromSchedule(Schedule.spaced(10.seconds).jittered)
    .mapM(_ =>
      currentTime(TimeUnit.MILLISECONDS)
        .map(sdf.format)
    )
  // end:doctag<stream>

  // doctag<factory>
  private def amqCF = JmsConnectionFactory(
    "amq:amq",
    new ActiveMQConnectionFactory("vm://simple?create=false"),
    3.seconds,
    Some(JmsKeepAliveMonitor(JmsQueue("keepAlive"), 1.second, 3))
  )
  // end:doctag<factory>

  private val clientId: String         = "sampleApp"
  private val testDest: JmsDestination = JmsQueue("sample")

  // doctag<program>
  private val program = for {
    _         <- putStrLn("Starting JMS Broker") *> ZIO.service[BrokerService]
    f         <- ZIO.unit.schedule(Schedule.duration(1.minutes)).fork
    jmsStream <- recoveringJmsStream(amqCF, clientId, testDest, 2.seconds)
    jmsSink   <- recoveringJmsSink(amqCF, clientId, testDest, 1.second)
    consumer  <- jmsStream.foreach(s => putStrLn(s)).fork
    producer  <- stream.run(jmsSink).fork
    _         <- f.join >>> consumer.interrupt >>> producer.interrupt
  } yield ()
  // end:doctag<program>

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    program
      .provideCustomLayer(combinedEnv)
      .catchAllCause(c => putStrLn(c.prettyPrint))
      .exitCode
}
