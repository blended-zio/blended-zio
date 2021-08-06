package blended.zio.streams.jms

import zio._
import zio.duration._
import zio.logging._
import zio.stream._

import blended.zio.streams.FlowEnvelope
import blended.zio.streams.jms.JmsApi._
import blended.zio.streams.jms.JmsApiObject._

object RecoveringJmsStream {

  def make(
    cf: JmsConnectionFactory,
    clientId: String,
    retryInterval: Duration
  ) = for {
    q <- zio.Queue.bounded[FlowEnvelope[_, _]](1)
  } yield new RecoveringJmsStream(cf, clientId, retryInterval) {
    override private[jms] val buffer: zio.Queue[FlowEnvelope[_, _]] = q
  }
}

sealed abstract class RecoveringJmsStream private (
  factory: JmsConnectionFactory,
  clientId: String,
  retryInterval: Duration
) {

  private[jms] val buffer: zio.Queue[FlowEnvelope[_, _]]

  // doctag<stream>
  def stream(
    dest: JmsDestination
  ) = {

    def consumeUntilException(cons: JmsConsumer) = jmsStream(cons).foreach(s => buffer.offer(s))

    def consumeForEver: ZIO[JmsEnv, Nothing, Unit] = {
      val part = for {
        _      <- log.debug(s"Trying to recover consumer for [${factory.id}] with destination [$dest]")
        conMgr <- ZIO.service[JmsConnectionManager.Service]
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
