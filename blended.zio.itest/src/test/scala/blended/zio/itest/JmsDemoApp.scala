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
import blended.zio.streams.jms.JmsApi._
import blended.zio.streams.jms.JmsApiObject._
import blended.zio.streams.jms.JmsDestination._
import blended.zio.streams.jms._

import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.broker.BrokerService

object JmsDemoApp extends App {

  private val sdf: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH.mm.ss.SSS")

  // doctag<layer>
  private val logEnv = ZEnv.live ++ Slf4jLogger.make((_, message) => message)

  private val brokerEnv = logEnv >>> AMQBroker.simple("simple")

  private val combinedEnv = logEnv ++ brokerEnv ++ defaultJmsEnv(logEnv)
  // end:doctag<layer>

  // doctag<stream>
  private val stream = ZStream
    .fromSchedule(Schedule.spaced(500.millis).jittered)
    .mapM(_ =>
      currentTime(TimeUnit.MILLISECONDS)
        .map(sdf.format)
        .map(s => FlowEnvelope.make(s))
    )
  // end:doctag<stream>

  private val testDest: JmsDestination = JmsQueue("sample")
  private val cf: JmsConnectionFactory =
    JmsConnectionFactory(
      id = "amq:amq",
      factory = new ActiveMQConnectionFactory("vm://simple?create=false"),
      reconnectInterval = 5.seconds
    )

  // doctag<producer>
  private def producer(con: JmsConnection) =
    createSession(con).use(session =>
      createProducer(session).use(prod => stream.run(jmsSink(prod, testDest, stringEnvelopeEncoder)))
    )
  // end:doctag<producer>

  // doctag<consumer>
  private def consumer(con: JmsConnection) =
    createSession(con).use { session =>
      createConsumer(session, testDest).use { cons =>
        jmsStream(cons).foreach(s => putStrLn(s.toString()))
      }
    }
  // end:doctag<consumer>

  // doctag<program>
  private val program =
    for {
      _      <- putStrLn("Starting JMS Broker") *> ZIO.service[BrokerService]
      conMgr <- ZIO.service[JmsConnectionManager.Service]
      _      <- (for {
                  con <- conMgr.connect(cf, "sample")
                  _   <- conMgr.reconnect(con, Some(new Exception("Boom"))).schedule(Schedule.duration(10.seconds)).fork
                  _   <- for {
                           c <- consumer(con).fork
                           p <- producer(con).fork
                           _ <- c.join
                           _ <- p.join
                         } yield ()
                } yield ())
    } yield ()
  // end:doctag<program>

  // doctag<run>
  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = program
    .provideCustomLayer(combinedEnv)
    .catchAllCause(c => putStrLn(c.prettyPrint))
    .exitCode
  // end:doctag<run>
}
