package blended.zio.itest

import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat

import zio._
import zio.console._
import zio.clock._
import zio.duration._
import zio.logging._
import zio.logging.slf4j._

import org.apache.activemq.broker.BrokerService
import org.apache.activemq.ActiveMQConnectionFactory
import zio.stream.ZStream

import blended.zio.activemq.AMQBroker

import blended.zio.streams._
import blended.zio.streams.jms._
import JmsApi._
import JmsApiObject._
import JmsDestination._

object JmsStreamDemo extends App {

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

  private def producer(con: JmsConnection) =
    createSession(con).use(session =>
      createProducer(session).use(prod => stream.run(jmsSink(prod, testDest, stringEnvelopeEncoder)))
    )

  // doctag<program>
  private val program =
    for {
      _      <- ZIO.service[BrokerService]
      _      <- log.info("ActiveMW Broker started")
      conMgr <- ZIO.service[JmsConnectionManager.Service]
      con    <- conMgr.connect(cf, "sample")
      // Start a producer with sample messages
      _      <- producer(con)
      // Simulate a reconnect
      _      <- conMgr.reconnect(con, Some(new Exception("Boom"))).schedule(Schedule.duration(10.seconds)).fork
      // Finally create a stream, consuming the messages from the JMS Endpoint
      _      <- EndpointManager.createStream(JmsEndpoint(cf, "endpoint", testDest)).foreach(env => putStrLn(s"$env"))
    } yield ()

  // end:doctag<program>

  // doctag<run>
  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = program
    .provideCustomLayer(combinedEnv)
    .catchAllCause(c => putStrLn(c.prettyPrint))
    .exitCode
  // end:doctag<run>
}
