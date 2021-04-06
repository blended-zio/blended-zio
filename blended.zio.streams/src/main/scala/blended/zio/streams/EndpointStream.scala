package blended.zio.streams

import zio._
import zio.clock._
import zio.logging._
import zio.stream._

object EndpointStream {

  def make[R, I, T](ep: Endpoint[R, I, T]) = for {
    buf  <- Queue.bounded[FlowEnvelope[I, T]](1)
    epStr = new EndpointStream(ep, buf)
    str  <- epStr.stream
  } yield str
}

sealed private class EndpointStream[R, I, T](
  ep: Endpoint[R, I, T],
  buffer: zio.Queue[FlowEnvelope[I, T]]
) {

  // doctag<stream>
  def stream = {

    def consumeUntilException =
      ZStream.fromEffect(ep.poll).collect { case Some(o) => o }.foreach(s => buffer.offer(s))

    def consumeForEver: ZIO[R with Logging with Clock, Throwable, Unit] = {
      val part = for {
        _ <- log.debug(s"Trying to recover endpoint stream [${ep.id}]")
        _ <- ep.init
        _ <- consumeUntilException
      } yield ()

      part.catchAll { _ =>
        for {
          f <- consumeForEver.schedule(Schedule.duration(ep.retryInterval)).fork
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
