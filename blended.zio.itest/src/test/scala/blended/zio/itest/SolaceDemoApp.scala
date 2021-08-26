package blended.zio.itest

import java.text.SimpleDateFormat
import java.util.Hashtable
import java.util.concurrent.TimeUnit

import zio._
import zio.clock._
import zio.console._
import zio.duration._
import zio.stream.ZStream

import blended.zio.streams._
import blended.zio.streams.jms.JmsApi._
import blended.zio.streams.jms.JmsApiObject._
import blended.zio.streams.jms.JmsDestination._
import blended.zio.streams.jms._

import com.solacesystems.jms.SolJmsUtility

object SolaceDemoApp extends App {

  private val sdf: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH.mm.ss.SSS")

  // doctag<stream>
  private val stream = ZStream
    .fromSchedule(Schedule.spaced(10.millis).jittered)
    .mapM(_ =>
      currentTime(TimeUnit.MILLISECONDS)
        .map(sdf.format)
        .map(s => FlowEnvelope.make(JmsMessageBody.Text(s)))
    )
  // end:doctag<stream>

  private val testDest: JmsDestination = JmsQueue("sample")
  private val cf: JmsConnectionFactory =
    JmsConnectionFactory(
      id = "solace:jms",
      factory = SolJmsUtility
        .createConnectionFactory(
          "devel.wayofquality.de",
          "sib",
          "sib123",
          "default",
          new Hashtable[Any, Any]()
        ),
      reconnectInterval = 5.seconds
    )

  // doctag<producer>
  private def producer(con: JmsConnection) =
    createSession(con).use(session => createProducer(session).use(prod => stream.run(jmsSink[FlowEnvelope[String, JmsMessageBody]](prod, testDest))))
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
      con <- JmsConnectionManager.connect(cf, "solace")
      f   <- getStrLn.fork
      _   <- consumer(con).fork
      _   <- producer(con).fork
      _   <- f.join *> JmsConnectionManager.shutdown
    } yield ()
  // end:doctag<program>

  // doctag<run>
  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = program
    .provideCustomLayer(JmsTestEnv.withoutBroker)
    .catchAllCause(c => putStrLn(c.prettyPrint))
    .exitCode
  // end:doctag<run>
}
