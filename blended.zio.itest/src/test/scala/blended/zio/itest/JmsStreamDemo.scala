package blended.zio.itest

import zio._
import zio.console._
import zio.duration._
import zio.logging._
import zio.logging.slf4j._

import org.apache.activemq.broker.BrokerService
import org.apache.activemq.ActiveMQConnectionFactory

import blended.zio.activemq.AMQBroker

import blended.zio.streams._
import blended.zio.streams.jms._
import JmsApi._
import JmsApiObject._
import JmsDestination._
import java.util.UUID

object JmsStreamDemo extends App {

  // doctag<layer>
  private val logEnv = ZEnv.live ++ Slf4jLogger.make((_, message) => message)

  private val brokerEnv = logEnv >>> AMQBroker.simple("simple")

  private val combinedEnv = logEnv ++ brokerEnv ++ defaultJmsEnv(logEnv)
  // end:doctag<layer>

  private val testDest: JmsDestination = JmsQueue("sample")

  private val cf: JmsConnectionFactory =
    JmsConnectionFactory(
      id = "amq:amq",
      factory = new ActiveMQConnectionFactory("vm://simple?create=false"),
      reconnectInterval = 5.seconds
    )

  // doctag<program>
  private val program =
    for {
      _ <- ZIO.service[BrokerService]
      _ <- log.info("ActiveMQ Broker started")
      _ <- JmsEndpoint.make(cf, "endpoint", testDest).use { ep =>
             for {
               _   <- ep.connect
               _   <- ep.send(
                        FlowEnvelope
                          .make(UUID.randomUUID().toString(), "Hallo Andreas")
                          .addHeader(EnvelopeHeader(Map("foo" -> "bar")))
                      )(
                        _.mapContent(c => JmsMessageBody.Text(c))
                      )
               env <- ep.nextEnvelope
               _   <- putStrLn(env.toString())
             } yield ()
           }
    } yield ()
  // end:doctag<program>

  // doctag<run>
  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = program
    .provideCustomLayer(combinedEnv)
    .catchAllCause(c => putStrLn(c.prettyPrint))
    .exitCode
  // end:doctag<run>
}
