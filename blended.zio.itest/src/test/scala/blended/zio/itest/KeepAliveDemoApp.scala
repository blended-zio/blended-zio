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

import org.apache.activemq.ActiveMQConnectionFactory
import blended.zio.streams.jms._

object KeepAliveDemoApp extends App {

  private val sdf: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH.mm.ss.SSS")

  // doctag<stream>
  private val stream: ZStream[ZEnv, Nothing, FlowEnvelope[String, JmsMessageBody]] = ZStream
    .fromSchedule(Schedule.spaced(10.seconds).jittered)
    .mapM(_ =>
      currentTime(TimeUnit.MILLISECONDS)
        .map(sdf.format)
        .map(s => FlowEnvelope.make(JmsMessageBody.Text(s)))
    )
  // end:doctag<stream>

  // doctag<factory>
  private def amqCF = JmsConnectionFactory(
    id = "amq:amq",
    factory = new ActiveMQConnectionFactory("vm://simple?create=false"),
    reconnectInterval = 3.seconds,
    keepAlive = Some(JmsKeepAliveMonitor(JmsQueue("keepAlive"), 1.second, 3))
  )
  // end:doctag<factory>

  private val clientId: String         = "sampleApp"
  private val testDest: JmsDestination = JmsQueue("sample")

  // doctag<program>
  private val program = for {
    con      <- JmsConnectionManager.connect(amqCF, clientId)
    jmsStream = JmsApi.recoveringsJmsStream(con, testDest, 2.seconds)
    jmsSink   = JmsApi.recoveringJmsSink[FlowEnvelope[String, JmsMessageBody]](con, testDest, 1.second)
    consumer <- jmsStream.foreach(s => putStrLn(s.toString())).fork
    producer <- stream.run(jmsSink).fork
    _        <- (consumer.interrupt >>> producer.interrupt).schedule(Schedule.duration(1.minutes))
  } yield ()
  // end:doctag<program>

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    program
      .provideCustomLayer(JmsTestEnv.withBroker)
      .catchAllCause(c => putStrLn(c.prettyPrint))
      .exitCode
}
