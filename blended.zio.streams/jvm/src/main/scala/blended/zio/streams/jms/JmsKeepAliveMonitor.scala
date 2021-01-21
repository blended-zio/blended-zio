package blended.zio.streams.jms

import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import javax.jms.JMSException

import zio._
import zio.clock._
import zio.duration._
import zio.logging._
import blended.zio.streams._
import zio.stream._

import ZIOJmsConnectionManager._

object JmsKeepAliveMonitor {

  private val sdf: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss:SSS")

  // doctag<run>
  def run(
    con: JmsConnection,
    dest: JmsDestination,
    interval: Duration,
    allowed: Int
  ): ZIO[ZEnv with Logging with ZIOJmsConnectionManager, JMSException, Int] = for {
    kam  <- DefaultKeepAliveMonitor.make(s"${con.id}-KeepAlive", allowed)
    send <- startKeepAliveSender(con, dest, interval).fork
    rec  <- startKeepAliveReceiver(con, dest, kam).fork
    f    <- kam.run(interval).fork
    _    <- f.join
    _    <- send.interrupt *> rec.interrupt
    c    <- kam.current
  } yield (c)
  // end:doctag<run>

  // doctag<sender>
  private def startKeepAliveSender(
    con: JmsConnection,
    dest: JmsDestination,
    interval: Duration
  ): ZIO[ZEnv with Logging, JMSException, Unit] = {

    val stream: ZStream[ZEnv, Nothing, String] = ZStream
      .fromSchedule(Schedule.spaced(interval))
      .mapM(_ =>
        currentTime(TimeUnit.MILLISECONDS)
          .map(t => s"KeepAlive ($con) : ${sdf.format(t)}")
      )

    createSession(con).use(jmsSess => createProducer(jmsSess).use(prod => stream.run(jmsSink(prod, dest))))
  }
  // end:doctag<sender>

  // doctag<receiver>
  private def startKeepAliveReceiver(
    con: JmsConnection,
    dest: JmsDestination,
    kam: KeepAliveMonitor
  ): ZIO[ZEnv with Logging, JMSException, Unit] = createSession(con).use { jmsSess =>
    createConsumer(jmsSess, dest).use(cons => jmsStream(cons).foreach(_ => kam.alive))
  }
  // end:doctag<receiver>
}
