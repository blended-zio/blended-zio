package blended.zio.streams

import zio._
import zio.clock._
import zio.logging._
import zio.stream._

import blended.zio.streams.FlowEnvelope

object EndpointStreams {

  def stream[I, T](
    ep: Endpoint[I, T]
  ) = zio.Queue.bounded[FlowEnvelope[I, T]](1).flatMap { q =>
    new RecoveringEndpointStream[I, T](ep) {
      override val buffer: zio.Queue[FlowEnvelope[I, T]] = q
    }.stream
  }

  def sink[I, T](
    ep: Endpoint[I, T]
  ) = zio.Queue.bounded[FlowEnvelope[I, T]](1).flatMap { q =>
    new RecoveringEndpointSink[I, T](ep) {
      override val buffer: zio.Queue[FlowEnvelope[I, T]] = q
    }.sink
  }

  sealed abstract class RecoveringEndpointStream[I, T] private[EndpointStreams] (
    ep: Endpoint[I, T]
  ) {

    private[EndpointStreams] val buffer: zio.Queue[FlowEnvelope[I, T]]

    def stream = {

      def consumeForEver: ZIO[Clock with Logging, Nothing, Unit] = {
        val part = Stream.repeatEffect(ep.nextEnvelope).collect { case Some(env) => env }.foreach(buffer.offer(_))

        part.catchAll { _ =>
          for {
            f <- consumeForEver.schedule(Schedule.duration(ep.config.recoveryInterval)).fork
            _ <- f.join
          } yield ()
        }
      }

      for {
        _ <- consumeForEver.fork
        s <- ZIO.succeed(ZStream.repeatEffect(buffer.take))
      } yield s
    }
  }

  sealed abstract class RecoveringEndpointSink[I, T] private[EndpointStreams] (
    ep: Endpoint[I, T]
  ) {
    private[EndpointStreams] val buffer: zio.Queue[FlowEnvelope[I, T]]

    def sink = {

      def produceForever: ZIO[Clock with Logging, Nothing, Unit] = {

        val part = buffer.take.flatMap(e => ep.send(e) *> e.ackOrDeny).forever

        part.catchAll { _ =>
          for {
            f <- produceForever.schedule(Schedule.duration(ep.config.recoveryInterval)).fork
            _ <- f.join
          } yield ()
        }
      }

      for {
        _ <- produceForever.fork
        s <- ZIO.succeed(ZSink.foreach(msg => buffer.offer(msg)))
      } yield s
    }
  }
}
