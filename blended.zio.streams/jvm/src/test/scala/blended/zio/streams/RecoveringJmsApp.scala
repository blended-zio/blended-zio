package blended.zio.streams

import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat

import zio._
import zio.console._
import zio.clock._
import zio.duration._
import zio.logging._
import zio.logging.slf4j._

import blended.zio.activemq.AMQBroker
import org.apache.activemq.broker.BrokerService
import blended.zio.streams.jms._
import org.apache.activemq.ActiveMQConnectionFactory
import zio.stream.ZStream

object RecoveringJmsApp extends App {

  private val sdf: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH.mm.ss.SSS")

  private val logEnv: ZLayer[Any, Nothing, ZEnv with Logging] =
    ZEnv.live ++ Slf4jLogger.make((_, message) => message)

  private val brokerEnv: ZLayer[Any, Throwable, AMQBroker.AMQBroker] =
    logEnv >>> AMQBroker.simple("simple")

  private val combinedEnv: ZLayer[ZEnv, Throwable, ZEnv with AMQBroker.AMQBroker with Logging] =
    logEnv ++ brokerEnv

  // doctag<stream>
  private val stream: ZStream[ZEnv, Nothing, String] = ZStream
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
      3.seconds
    )

  private val clientId: String         = "sampleApp"
  private val testDest: JmsDestination = JmsQueue("sample")

  // doctag<program>
  private val program: ZIO[ZEnv with AMQBroker.AMQBroker with Logging, Throwable, Unit] = {
    val logic = for {
      mgr       <- ZIO.service[ZIOJmsConnectionManager.Service]
      _         <- putStrLn("Starting JMS Broker") *> ZIO.service[BrokerService]
      f         <- ZIO.unit.schedule(Schedule.duration(30.seconds)).fork
      _         <-
        mgr.reconnect(amqCF.connId(clientId), Some(new Exception("Boom"))).schedule(Schedule.duration(10.seconds)).fork
      jmsStream <- recoveringJmsStream(amqCF, clientId, testDest, 2.seconds)
      jmsSink   <- recoveringJmsSink(amqCF, clientId, testDest, 1.second)
      consumer  <- jmsStream.foreach(s => putStrLn(s)).fork
      producer  <- stream.run(jmsSink).fork
      _         <- f.join >>> consumer.interrupt >>> producer.interrupt
    } yield ()

    for {
      si <- ZIOJmsConnectionManager.Service.singleton
      _  <- logic.provideSomeLayer[ZEnv with AMQBroker.AMQBroker with Logging](ZIOJmsConnectionManager.Service.live(si))
    } yield ()
  }
  // end:doctag<program>

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = program
    .provideCustomLayer(combinedEnv)
    .catchAllCause(c => putStrLn(c.prettyPrint))
    .exitCode

}
