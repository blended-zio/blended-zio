package blended.zio.streams

import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Hashtable

import zio._
import zio.console._
import zio.clock._
import zio.duration._
import zio.logging.slf4j._

import zio.stream.ZStream

import blended.zio.streams.jms._
import JmsApi._
import JmsApiObject._
import JmsDestination._
import com.solacesystems.jms.SolJmsUtility

object JmsDemoApp extends App {

  private val sdf: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH.mm.ss.SSS")

  private val logEnv = ZEnv.live ++ Slf4jLogger.make((_, message) => message)

  private val combinedEnv = logEnv ++ defaultJmsEnv(logEnv)
  // end:doctag<layer>

  // doctag<stream>
  private val stream = ZStream
    .fromSchedule(Schedule.spaced(10.millis).jittered)
    .mapM(_ =>
      currentTime(TimeUnit.MILLISECONDS)
        .map(sdf.format)
        .map(s => FlowEnvelope.make(s))
    )
  // end:doctag<stream>

  private val testDest: JmsDestination = JmsQueue("sample")
  private val cf: JmsConnectionFactory =
    JmsConnectionFactory(
      id = "solace:jms",
      factory = SolJmsUtility
        .createConnectionFactory(
          "devel.wayofquality.de",
          "blended",
          "blended123",
          "default",
          new Hashtable[Any, Any]()
        ),
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
      conMgr <- ZIO.service[JmsConnectionManager.Service]
      con    <- conMgr.connect(cf, "zio")
      f      <- getStrLn.fork
      _      <- consumer(con).fork
      _      <- producer(con).fork
      _      <- f.join *> conMgr.close(con)
    } yield ()
  // end:doctag<program>

  // doctag<run>
  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = program
    .provideCustomLayer(combinedEnv)
    .catchAllCause(c => putStrLn(c.prettyPrint))
    .exitCode
  // end:doctag<run>
}
