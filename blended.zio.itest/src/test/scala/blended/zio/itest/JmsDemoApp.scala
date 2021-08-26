package blended.zio.itest

import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit

import zio._
import zio.clock._
import zio.console._
import zio.duration._
import zio.stream.ZStream

import blended.zio.streams._
import blended.zio.streams.jms.JmsApi
import blended.zio.streams.jms.JmsApiObject._
import blended.zio.streams.jms.JmsDestination._
import blended.zio.streams.jms.JmsConnectionManager
import blended.zio.streams.jms._
import blended.zio.streams.jms.JmsApi.JmsEncoder._

import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.broker.BrokerService

object JmsDemoApp extends App {

  private val sdf: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH.mm.ss.SSS")

  // doctag<stream>
  private val stream: ZStream[ZEnv, Nothing, FlowEnvelope[String, JmsMessageBody]] = ZStream
    .fromSchedule(Schedule.spaced(500.millis).jittered)
    .mapM(_ =>
      currentTime(TimeUnit.MILLISECONDS)
        .map(sdf.format)
        .map(s => FlowEnvelope.make(JmsMessageBody.Text(s)))
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
  def producer(con: JmsConnection) =
    JmsApi
      .createSession(con)
      .use(session =>
        JmsApi
          .createProducer(session)
          .use(prod => stream.run(JmsApi.jmsSink[FlowEnvelope[String, JmsMessageBody]](prod, testDest)))
      )
  // end:doctag<producer>

  // doctag<consumer>
  private def consumer(con: JmsConnection) =
    JmsApi.createSession(con).use { session =>
      JmsApi.createConsumer(session, testDest).use { cons =>
        JmsApi.jmsStream(cons).foreach(s => putStrLn(s.toString()))
      }
    }
  // end:doctag<consumer>

  // doctag<program>
  private val program =
    for {
      _ <- putStrLn("Starting JMS Broker") *> ZIO.service[BrokerService]
      _ <- (for {
             con <- JmsConnectionManager.connect(cf, "sample")
             _   <- JmsConnectionManager
                      .reconnect(con, Some(new Exception("Boom")))
                      .schedule(Schedule.duration(10.seconds))
                      .fork
             _   <- for {
                      p <- producer(con).fork
                      c <- consumer(con).fork
                      _ <- c.join
                      _ <- p.join
                    } yield ()
           } yield ())
    } yield ()
  // end:doctag<program>

  // doctag<run>
  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = program
    .provideCustomLayer(JmsTestEnv.withBroker)
    .catchAllCause(c => putStrLn(c.prettyPrint))
    .exitCode
  // end:doctag<run>
}
