package blended.zio.streams.jms

import zio._
import zio.duration._
import zio.logging._
import zio.stream._

import JmsApiObject._
import JmsApi._

private[jms] object RecoveringJmsSink {

  def make(
    cf: JmsConnectionFactory,
    clientId: String
  ) =
    for {
      q <- zio.Queue.bounded[String](1)
    } yield new RecoveringJmsSink(cf, clientId) {
      override private[jms] val buffer: zio.Queue[String] = q
    }
}

sealed abstract class RecoveringJmsSink private (
  factory: JmsConnectionFactory,
  clientId: String
) {
  private[jms] val buffer: zio.Queue[String]

  // doctag<sink>
  def sink(
    dest: JmsDestination,
    retryInterval: Duration
  ) = {

    def produceOne(p: JmsProducer) = buffer.take.flatMap { s: String =>
      JmsApi.send(s, p, dest)
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
