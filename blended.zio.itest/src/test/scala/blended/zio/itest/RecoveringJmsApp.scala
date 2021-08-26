package blended.zio.itest

import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit

import zio._
import zio.clock._
import zio.console._
import zio.duration._
import zio.stream.ZStream

import blended.zio.streams._
import blended.zio.streams.jms.JmsApiObject._
import blended.zio.streams.jms.JmsDestination._
import blended.zio.streams.jms._
import blended.zio.streams.jms.JmsConnectionManager.JmsConnectionManagerSvc
import blended.zio.streams.jms.JmsApi

import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.broker.BrokerService

object RecoveringJmsApp extends App {

  private val sdf = new SimpleDateFormat("yyyy-MM-dd-HH.mm.ss.SSS")

  // doctag<stream>
  private val stream: ZStream[ZEnv, Nothing, FlowEnvelope[String, JmsMessageBody]] = ZStream
    .fromSchedule(Schedule.spaced(1000.millis).jittered)
    .mapM(_ =>
      currentTime(TimeUnit.MILLISECONDS)
        .map(sdf.format)
        .map(s => FlowEnvelope.make(JmsMessageBody.Text(s)))
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
    _        <- putStrLn("Starting JMS Broker") *> ZIO.service[BrokerService]
    mgr      <- ZIO.service[JmsConnectionManagerSvc]
    con      <- mgr.connect(amqCF, clientId)
    f        <- ZIO.unit.schedule(Schedule.duration(30.seconds)).fork
    _        <- mgr
                  .reconnect(
                    con,
                    Some(new Exception("Boom"))
                  )
                  .schedule(Schedule.duration(10.seconds))
                  .fork
    jmsStream = JmsApi.recoveringsJmsStream(con, testDest, 2.seconds)
    jmsSink   = JmsApi.recoveringJmsSink[FlowEnvelope[String, JmsMessageBody]](con, testDest, 1.second)
    consumer <- jmsStream.foreach(s => putStrLn(s.toString())).fork
    producer <- stream.run(jmsSink).fork
    _        <- f.join >>> consumer.interrupt >>> producer.interrupt
  } yield ()

  // end:doctag<program>

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = program
    .provideCustomLayer(JmsTestEnv.withBroker)
    .catchAllCause(c => putStrLn(c.prettyPrint))
    .exitCode

}
