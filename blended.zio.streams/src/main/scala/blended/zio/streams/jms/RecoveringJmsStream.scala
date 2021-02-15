package blended.zio.streams.jms

import javax.jms._

import zio._
import zio.duration._
import zio.logging._
import zio.stream._

import blended.zio.streams.jms.ZIOJmsConnectionManager

private[jms] object RecoveringJmsStream {

  def make(
    cf: JmsConnectionFactory,
    clientId: String,
    retryInterval: Duration
  ) = for {
    q <- zio.Queue.bounded[String](1)
  } yield new RecoveringJmsStream(cf, clientId, retryInterval) {
    override private[jms] val buffer: zio.Queue[String] = q
  }
}

sealed abstract class RecoveringJmsStream private (
  factory: JmsConnectionFactory,
  clientId: String,
  retryInterval: Duration
) {

  private[jms] val buffer: zio.Queue[String]

  // doctag<stream>
  def stream(
    dest: JmsDestination
  ) = {

    def consumeUntilException(cons: JmsConsumer) = jmsStream(cons).collect { case tm: TextMessage =>
      tm.getText()
    }
      .foreach(s => buffer.offer(s))

    def consumeForEver: ZIO[ZEnv with Logging with ZIOJmsConnectionManager.ZIOJmsConnectionManager, Nothing, Unit] = {
      val part = for {
        _      <- log.debug(s"Trying to recover consumer for [${factory.id}] with destination [$dest]")
        conMgr <- ZIO.service[ZIOJmsConnectionManager.Service]
        con    <- conMgr.connect(factory, clientId)
        _      <- createSession(con).use(jmsSess => createConsumer(jmsSess, dest).use(c => consumeUntilException(c)))
      } yield ()

      part.catchAll { _ =>
        for {
          f <- consumeForEver.schedule(Schedule.duration(retryInterval)).fork
          _ <- f.join
        } yield ()
      }
    }

    for {
      _ <- consumeForEver.fork
      s <- ZIO.succeed(ZStream.repeatEffect(buffer.take))
    } yield s
  }
  // end:doctag<stream>
}
