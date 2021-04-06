package blended.zio.streams

import zio._
import zio.clock._
import zio.logging._
import zio.stream.ZStream
import zio.stream.ZTransducer
import zio.duration._

object Endpoint {

  def make[R, I, T, S](initial: S): Endpoint[R, I, T, S] =
    Endpoint[R, I, T, S](EndpointState(EndpointStateData.Created, Map.empty[I, FlowEnvelope[I, T]]), initial)

  sealed trait EndpointStateData
  private object EndpointStateData {
    case object Created    extends EndpointStateData
    case object Connected  extends EndpointStateData
    case object Recovering extends EndpointStateData
    case object Closed     extends EndpointStateData
  }
  final case class EndpointState[I, T](
    state: EndpointStateData,
    inflight: Map[I, FlowEnvelope[I, T]]
  )                                {
    val inflightCount = inflight.size

    def updateState(s: EndpointStateData): EndpointState[I, T] = ???

    def addInflight(env: FlowEnvelope[I, T]): EndpointState[I, T] = ???

    def removeInflight(env: FlowEnvelope[I, T]): EndpointState[I, T] = ???
  }
}

/**
 * An endpoint which requires an environment R.
 * It consumes messages from external sources and provides a stream of FlowEnvelopes[I,T].
 * The state is encapsulated in S, all functions of the endpoint yield a new state.
 */
final case class Endpoint[R, I, T, S] private (
  id: String,
  endpointState: Endpoint.EndpointState[I, T],
  msgState: S
) { self =>

  type Envelope = FlowEnvelope[I, T]

  /**
   * In case the endpoint encounters an exception while polling for a message,
   * it will enter a recovery period of [retryInterval] and then resume cosuming
   * messages.
   */
  val retryInterval: Duration

  /**
   * Initialise the underlying endpoint
   */
  def init(f: S => S): ZIO[R, Throwable, Endpoint[R, I, T, S]]

  /**
   * Definitely close the underlying Endpoint
   */
  def close(f: S => S): ZIO[R, Throwable, Endpoint[R, I, T, S]]

  /**
   * Get a single message from the underlying endpoint and wrap it into a FlowEnvelope.
   * The FlowEnvelope will be enriched with meta data specific to the endpoint.
   */
  def poll: ZIO[R, Throwable, Option[FlowEnvelope[I, T]]]

  /**
   * Send a given FlowEnvelope via the Endpoint. Metadata within the envelope may be
   * taken into account to further specify relevant send parameters.
   */
  def send[E](env: FlowEnvelope[I, T]): ZIO[R, E, FlowEnvelope[I, T]]

  def stream = for {
    buf  <- Queue.bounded[FlowEnvelope[I, T]](1)
    epStr = new EndpointStream(self, buf)
    str  <- epStr.stream
  } yield str

  sealed private class EndpointStream[R, I, T](
    ep: Endpoint[R, I, T, S],
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
}

object EndpointManager {

  // create a Stream using a given Endpoint
  def createStream[R, I, T, S](ep: Endpoint[R, I, T, S]): ZStream[R, Throwable, FlowEnvelope[I, T]] = ???

  implicit class ZStreamAddOns[R, E, A](self: ZStream[R, E, A]) {

    def sendTo[E1 >: E, I, T, S](
      ep: Endpoint[R, I, T, S]
    )(encode: A => (I, T)): ZStream[R, E1, FlowEnvelope[I, T]] =
      self.map(encode).map { case (i, c) => FlowEnvelope.make(i, c) }.transduce {
        ZTransducer.fromFunctionM(env => ep.send[E1](env))
      }
  }
}
