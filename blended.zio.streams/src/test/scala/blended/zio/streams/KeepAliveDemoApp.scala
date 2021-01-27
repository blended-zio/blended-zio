package blended.zio.streams

import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import javax.jms.JMSException

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

object KeepAliveDemoApp extends App {

  private val sdf: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH.mm.ss.SSS")

  private val logEnv: ZLayer[Any, Nothing, ZEnv with Logging] =
    ZEnv.live ++ Slf4jLogger.make((_, message) => message)

  private val brokerEnv: ZLayer[Any, Throwable, AMQBroker.AMQBroker] =
    logEnv >>> AMQBroker.simple("simple")

  private val combinedEnv: ZLayer[ZEnv, Nothing, ZEnv with AMQBroker.AMQBroker with Logging] =
    (logEnv ++ brokerEnv).orDie

  // doctag<stream>
  private val stream: ZStream[ZEnv, Nothing, String] = ZStream
    .fromSchedule(Schedule.spaced(1000.millis).jittered)
    .mapM(_ =>
      currentTime(TimeUnit.MILLISECONDS)
        .map(sdf.format)
    )
  // end:doctag<stream>

  private val allowedKeepAlives: Int = 3

  // doctag<keepalive>
  private def keepAlive(
    conMgr: SingletonService[ZIOJmsConnectionManager.Service]
  ): JmsConnection => ZIO[ZEnv with Logging, Nothing, Unit] = { c =>
    val ka = ZIO
      .ifM(
        JmsKeepAliveMonitor
          .run(c, JmsQueue("keepAlive"), 1.second, 3)
          .map(_ == allowedKeepAlives)
      )(
        reconnect(c, Some(new KeepAliveException(c.id, allowedKeepAlives))),
        ZIO.unit
      )

    val monitor: ZIO[ZEnv with Logging with ZIOJmsConnectionManager.ZIOJmsConnectionManager, JMSException, Unit] = for {
      f <- ka.fork
      _ <- log.trace(s"Waiting for keep alive monitor for [$c]")
      _ <- f.join
    } yield ()

    val effect = for {
      // We need to specify the left over !!!
      _ <- monitor.provideSomeLayer[ZEnv with Logging](ZIOJmsConnectionManager.Service.live(conMgr)).forkDaemon
    } yield ()

    effect
  }
  // end:doctag<keepalive>

  // doctag<factory>
  private def amqCF(si: SingletonService[ZIOJmsConnectionManager.Service]): JmsConnectionFactory = JmsConnectionFactory(
    "amq:amq",
    new ActiveMQConnectionFactory("vm://simple?create=false"),
    3.seconds,
    keepAlive(si)
  )
  // end:doctag<factory>

  private val clientId: String         = "sampleApp"
  private val testDest: JmsDestination = JmsQueue("sample")

  // doctag<program>
  private val program: ZIO[ZEnv with AMQBroker.AMQBroker with Logging, Throwable, ExitCode] = {

    def logic(si: SingletonService[ZIOJmsConnectionManager.Service]) = for {
      _         <- putStrLn("Starting JMS Broker") *> ZIO.service[BrokerService]
      f         <- ZIO.unit.schedule(Schedule.duration(30.minutes)).fork
      jmsStream <- recoveringJmsStream(amqCF(si), clientId, testDest, 2.seconds)
      jmsSink   <- recoveringJmsSink(amqCF(si), clientId, testDest, 1.second)
      consumer  <- jmsStream.foreach(s => putStrLn(s)).fork
      producer  <- stream.run(jmsSink).fork
      _         <- f.join >>> consumer.interrupt >>> producer.interrupt
    } yield ()

    for {
      si <- ZIOJmsConnectionManager.Service.singleton
      _  <-
        logic(si).provideSomeLayer[ZEnv with AMQBroker.AMQBroker with Logging](ZIOJmsConnectionManager.Service.live(si))
    } yield ExitCode.success
  }
  // end:doctag<program>

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    (
      program
        .catchAll(_ => ZIO.succeed(ExitCode.failure))
      )
      .provideCustomLayer(combinedEnv)

}
