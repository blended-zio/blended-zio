package blended.zio.streams

import zio._
import zio.clock._
import zio.duration._
import zio.logging._
import zio.random._

// A connector that requires an environment R and works on top of FlowEnvelope[I,T]
trait Connector[R, I, T] {

  // Effectfully connect the endpoint to the underlying technology
  def start: ZIO[R, Throwable, Unit]

  // Effectfully disconnect from the underlying technology
  def stop: ZIO[R, Throwable, Unit]

  // Get the next message from the underlying technology
  def nextEnvelope: ZIO[R, Throwable, Option[FlowEnvelope[I, T]]]

  // Send a given envelope to the underlying technology
  def sendEnvelope(f: FlowEnvelope[I, T]): ZIO[R, Throwable, FlowEnvelope[I, T]]
}

// An endpoint that operates on FlowEnvelope[I,T]
trait Endpoint[I, T] {

  type R
  val id: String

  // We delegate to connector start if and only if the endpoint is in Created state
  def connect: ZIO[Clock with Logging, Throwable, Unit]

  // To disconnect we need to deny all current inflight envelopes and then go back to Created state
  def disconnect: ZIO[Clock with Logging, Throwable, Unit]

  // We can only send messages in Started state
  // If called in any other state we will try to initiate a recovery for the endpoint
  def send(env: FlowEnvelope[I, T]): ZIO[Clock with Logging, Throwable, FlowEnvelope[I, T]]

  def nextEnvelope: ZIO[Clock with Logging, Throwable, Option[FlowEnvelope[I, T]]]
}

object Endpoint {

  final case class EndpointConfig private (
    // The number of parallel inflight envelopes the endpoint can handle
    capacity: Int,
    // In case we didn't get a message from the backend, we will only try to get
    // the next message after 'pollInterval', otherwise we will try to get new messages
    // as fast as possible
    pollInterval: Duration,
    // In case the endpoint runs into an exception, we will have a default error
    // handler that silences the endpoint for a while and then reinitialises it
    recoveryInterval: Duration
  )

  val defaultEndpointConfig = EndpointConfig(1, 100.millis, 1.second)

  def endpointConfig(
    capacity: Int = defaultEndpointConfig.capacity,
    poll: Duration = defaultEndpointConfig.pollInterval,
    recover: Duration = defaultEndpointConfig.recoveryInterval
  ): EndpointConfig =
    EndpointConfig(
      if (capacity <= 0) defaultEndpointConfig.capacity else capacity,
      if (poll.toNanos <= 0) defaultEndpointConfig.pollInterval else poll,
      if (recover.toNanos <= 0) defaultEndpointConfig.recoveryInterval else recover
    )

  sealed private trait EndpointStateDetail
  private object EndpointStateDetail {
    // An endpoint whenn it is first created
    case object Created    extends EndpointStateDetail
    // An endpoint that is completely started and can be used to send an receive messages
    case object Started    extends EndpointStateDetail
    // An endpoint that is currently in recovery
    case object Recovering extends EndpointStateDetail
  }

  private case class EndpointState[I, T](
    state: EndpointStateDetail,
    inflight: Chunk[FlowEnvelope[I, T]]
  )

  private trait EndpointImpl[I, T] {

    type R

    val id: String
    val environment: R with Clock with Logging
    val config: EndpointConfig
    val connector: Connector[R, I, T]
    val state: RefM[EndpointState[I, T]]

    // We delegate to connector start if and only if the endpoint is in Created state
    final def connect: ZIO[Clock with Logging, Throwable, Unit] = state.updateSome {
      case EndpointState(EndpointStateDetail.Created, _) =>
        connector.start.map(_ => EndpointState[I, T](EndpointStateDetail.Started, Chunk.empty)).provide(environment)
    }

    // To disconnect we need to deny all current inflight envelopes and then go back to Created state
    final def disconnect: ZIO[Clock with Logging, Throwable, Unit] = state.updateSome {
      case EndpointState(EndpointStateDetail.Started, inflight) =>
        for {
          _ <- ZIO.foreach(inflight)(_.deny)
          _ <- connector.stop.provide(environment)
        } yield EndpointState(EndpointStateDetail.Created, Chunk.empty)
    }

    // We can only send messages in Started state
    // If called in any other state we will try to initiate a recovery for the endpoint
    final def send(
      env: FlowEnvelope[I, T]
    ): ZIO[Clock with Logging, Throwable, FlowEnvelope[I, T]] =
      (for {
        cs  <- state.get
        env <- cs.state match {
                 case EndpointStateDetail.Started => connector.sendEnvelope(env)
                 case s                           => ZIO.fail(new IllegalStateException(s"Endpoint [$id] cannot send messages in state [$s]"))
               }
      } yield env).tapError(recover(_)).provide(environment)

    final def nextEnvelope: ZIO[Clock with Logging, Throwable, Option[FlowEnvelope[I, T]]] =
      (for {
        cs  <- state.get
        env <- cs.state match {
                 case EndpointStateDetail.Started =>
                   if (cs.inflight.size < config.capacity) {
                     for {
                       env <- connector.nextEnvelope
                       _   <-
                         ZIO.foreach(env)(env => state.update(s => ZIO.succeed(s.copy(inflight = s.inflight :+ env))))
                     } yield env
                   } else {
                     ZIO.none
                   }
                 case _                           => ZIO.none
               }
      } yield env).tapError(recover(_)).provide(environment)

    // To recover, we first disconnect the endpoint and then schedule a state change to "created"
    // Then we can try to reconnect
    private def recover(t: Throwable): ZIO[R with Clock with Logging, Throwable, Unit] =
      log.info(s"Starting recovery for Endpoint [$id] : [${t.getMessage()}]") *>
        state.updateSome {
          case EndpointState(EndpointStateDetail.Started, _) =>
            ZIO.succeed(EndpointState[I, T](EndpointStateDetail.Recovering, Chunk.empty)) <*
              disconnect <* (reset *> connect).schedule(Schedule.duration(config.recoveryInterval)).forkDaemon
          case EndpointState(EndpointStateDetail.Created, _) =>
            ZIO.succeed(EndpointState[I, T](EndpointStateDetail.Recovering, Chunk.empty))
        }

    private def reset: ZIO[Clock with Logging, Nothing, Unit] =
      state.updateSome { case EndpointState(EndpointStateDetail.Recovering, _) =>
        ZIO.succeed(EndpointState[I, T](EndpointStateDetail.Created, Chunk.empty))
      }
  }

  def make[R1, I1, T1](
    con: Connector[R1, I1, T1],
    cfg: EndpointConfig
  ): ZIO[R1 with Clock with Logging, Nothing, Endpoint[I1, T1]] = for {
    r <- ZIO.environment[R1 with Clock with Logging]
    s <- RefM.make(EndpointState[I1, T1](EndpointStateDetail.Created, Chunk.empty))
    ep = new EndpointImpl[I1, T1] {
           type R = R1
           override val id          = nextUUID.toString
           override val environment = r
           override val config      = cfg
           override val connector   = con
           override val state       = s
         }
  } yield new Endpoint[I1, T1] {
    type R = ep.R
    override val id = ep.id

    override def connect: ZIO[Clock with Logging, Throwable, Unit] = ep.connect
    override def disconnect: ZIO[Clock with Logging, Throwable, Unit] = ep.disconnect
    override def nextEnvelope: ZIO[Clock with Logging, Throwable, Option[FlowEnvelope[I1, T1]]] = ep.nextEnvelope
    override def send(env: FlowEnvelope[I1, T1]): ZIO[Clock with Logging, Throwable, FlowEnvelope[I1, T1]] =
      ep.send(env)
  }

}
