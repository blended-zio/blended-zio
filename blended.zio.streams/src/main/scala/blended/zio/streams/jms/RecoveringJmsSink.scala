package blended.zio.streams.jms

import zio._
import zio.duration._
import zio.logging._
import zio.stream._

import blended.zio.streams.FlowEnvelope

import JmsApiObject._
import JmsApi._

object RecoveringJmsSink {

  def make[T](
    cf: JmsConnectionFactory,
    clientId: String,
    encode: JmsEncoder[T]
  ) =
    for {
      q <- zio.Queue.bounded[FlowEnvelope[_, T]](1)
    } yield new RecoveringJmsSink(cf, clientId, encode) {
      override private[jms] val buffer: zio.Queue[FlowEnvelope[_, T]] = q
    }
}

sealed abstract class RecoveringJmsSink[T] private (
  factory: JmsConnectionFactory,
  clientId: String,
  encode: JmsEncoder[T]
) {
  private[jms] val buffer: zio.Queue[FlowEnvelope[_, T]]

  // doctag<sink>
  def sink(
    dest: JmsDestination,
    retryInterval: Duration
  ) = {

    def produceOne(p: JmsProducer) = buffer.take.flatMap { s: FlowEnvelope[_, T] =>
      JmsApi.send(s, p, dest, encode)
    }

    def produceForever: ZIO[JmsEnv, Nothing, Unit] = {
      val part = for {
        _      <- log.debug(s"Trying to recover producer for [${factory.id}] with destination [$dest]")
        conMgr <- ZIO.service[JmsConnectionManager.Service]
        con    <- conMgr.connect(factory, clientId)
        _      <- createSession(con).use { jmsSess =>
                    createProducer(jmsSess).use { p =>
                      for {
                        f <- produceOne(p).forever.fork
                        _ <- f.join
                      } yield ()
                    }
                  }
      } yield ()

      part.catchAll { _ =>
        for {
          f <- produceForever.schedule(Schedule.duration(retryInterval)).fork
          _ <- f.join
        } yield ()
      }
    }

    for {
      _ <- produceForever.fork
      s <- ZIO.succeed(ZSink.foreach(msg => buffer.offer(msg)))
    } yield s
  }
  // end:doctag<sink>
}
