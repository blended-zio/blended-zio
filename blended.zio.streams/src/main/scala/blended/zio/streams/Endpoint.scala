package blended.zio.streams

import zio._
import zio.clock._
import zio.duration._
import zio.logging._
import zio.stream._

// A connector that works on top of FlowEnvelope[I,T]
trait Connector[I, T] {

  // Effectfully connect the endpoint to the underlying technology
  def start: ZIO[Any, Throwable, Unit]

  // Effectfully disconnect from the underlying technology
  def stop: ZIO[Any, Throwable, Unit]

  // Get the next message from the underlying technology
  def nextEnvelope: ZIO[Any, Throwable, Option[FlowEnvelope[I, T]]]

  // Send a given envelope to the underlying technology
  def sendEnvelope(f: FlowEnvelope[I, T]): ZIO[Any, Throwable, FlowEnvelope[I, T]]
}

// An endpoint that operates on FlowEnvelope[I,T]
trait Endpoint[I, T] {

  val id: String

  val config: Endpoint.EndpointConfig

  // We delegate to connector start if and only if the endpoint is in Created state
  def connect: ZIO[Any, Throwable, Unit]

  // To disconnect we need to deny all current inflight envelopes and then go back to Created state
  def disconnect: ZIO[Any, Throwable, Unit]

  // We can only send messages in Started state
  // If called in any other state we will try to initiate a recovery for the endpoint
  def send(env: FlowEnvelope[I, T]): ZIO[Any, Throwable, FlowEnvelope[I, T]]

  def nextEnvelope: ZIO[Any, Throwable, Option[FlowEnvelope[I, T]]]

  def stream: ZIO[Any, IllegalStateException, ZStream[Any, Nothing, FlowEnvelope[I, T]]]

  def sink
    : ZIO[Any, IllegalStateException, ZSink[Any, IllegalStateException, FlowEnvelope[I, T], FlowEnvelope[I, T], Unit]]
}

object Endpoint {

  def managed[I1, T1](
    eid: String,
    con: Connector[I1, T1],
    cfg: EndpointConfig
  ) =
    ZManaged.make(make(eid, con, cfg))(
      _.disconnect.catchAll(t => log.warn(s"Error disconnecting Endpoint [$eid] : ${t.getMessage()}"))
    )

  def make[I1, T1](
    eid: String,
    con: Connector[I1, T1],
    cfg: EndpointConfig
  ): ZIO[Logging, Nothing, Endpoint[I1, T1]] = for {
    epLogger <- ZIO.service[Logger[String]]
    epState  <- RefM.make(EndpointState[I1, T1](EndpointStateDetail.Created, Chunk.empty))
    strRef   <- Ref.make[Option[ZStream[Any, Nothing, FlowEnvelope[I1, T1]]]](None)
    skRef    <-
      Ref.make[Option[ZSink[Any, Nothing, FlowEnvelope[I1, T1], FlowEnvelope[I1, T1], Unit]]](None)
    prom     <- RefM.make[Option[Promise[Nothing, Boolean]]](None)
    ep        = new EndpointImpl[I1, T1] {
                  override val id                          = eid
                  override val logger                      = epLogger
                  override val config                      = cfg
                  override val connector                   = con
                  override val state                       = epState
                  override private[Endpoint] val streamRef = strRef
                  override private[Endpoint] val sinkRef   = skRef
                  override private[Endpoint] val stop      = prom
                }
  } yield new Endpoint[I1, T1] {
    override val id                                                                                   = ep.id
    override val config: EndpointConfig                                                               = cfg
    override def connect: ZIO[Any, Throwable, Unit]                                                   = ep.connect
    override def disconnect: ZIO[Any, Throwable, Unit]                                                = ep.disconnect
    override def nextEnvelope: ZIO[Any, Throwable, Option[FlowEnvelope[I1, T1]]]                      = ep.nextEnvelope
    override def send(env: FlowEnvelope[I1, T1]): ZIO[Any, Throwable, FlowEnvelope[I1, T1]]           =
      ep.send(env)
    override def stream: ZIO[Any, IllegalStateException, ZStream[Any, Nothing, FlowEnvelope[I1, T1]]] =
      ep.stream
    override def sink: ZIO[
      Any,
      IllegalStateException,
      ZSink[Any, Nothing, FlowEnvelope[I1, T1], FlowEnvelope[I1, T1], Unit]
    ]                                                                                                 = ep.sink
  }

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

    val id: String
    val config: EndpointConfig
    val connector: Connector[I, T]
    val state: RefM[EndpointState[I, T]]
    val logger: Logger[String]

    private[Endpoint] val streamRef: Ref[Option[ZStream[Any, Nothing, FlowEnvelope[I, T]]]]
    private[Endpoint] val sinkRef: Ref[
      Option[ZSink[Any, Nothing, FlowEnvelope[I, T], FlowEnvelope[I, T], Unit]]
    ]
    private[Endpoint] val stop: RefM[Option[Promise[Nothing, Boolean]]]

    override def toString(): String = s"${getClass().getSimpleName()}($id)"

    // We delegate to connector start if and only if the endpoint is in Created state
    final def connect: ZIO[Any, Throwable, Unit] = state.updateSome {
      case EndpointState(EndpointStateDetail.Created, _) =>
        connector.start.map(_ => EndpointState[I, T](EndpointStateDetail.Started, Chunk.empty))
    }

    // To disconnect we need to deny all current inflight envelopes and then go back to Created state
    final def disconnect: ZIO[Any, Throwable, Unit] = state.updateSome {
      case EndpointState(EndpointStateDetail.Started, inflight) =>
        for {
          _ <- logger.info(s"Stopping endpoint [$id] with [${inflight.size}] inflight messages.")
          // TODO: Reactivate this
          //_ <- ZIO.foreach(inflight)(_.deny)
          _ <- logger.trace(s"Finished denying inflight messages in [$id]")
          _ <- stop.get.flatMap(ZIO.foreach(_)(_.complete(ZIO.succeed(true))))
          _ <- logger.trace(s"Flagged connector stop for endpoint [$id]")
          _ <- stop.set(None) *> streamRef.set(None) *> sinkRef.set(None)
          _ <- logger.trace(s"About to invoke connector stop for endpoint [$id]")
          _ <- connector.stop
        } yield EndpointState(EndpointStateDetail.Created, Chunk.empty)
    }

    // We can only send messages in Started state
    // If called in any other state we will try to initiate a recovery for the endpoint
    final def send(
      env: FlowEnvelope[I, T]
    ): ZIO[Any, Throwable, FlowEnvelope[I, T]] =
      (for {
        cs  <- state.get
        env <- cs.state match {
                 case EndpointStateDetail.Started => connector.sendEnvelope(env)
                 case s                           => ZIO.fail(new IllegalStateException(s"Endpoint [$id] cannot send messages in state [$s]"))
               }
      } yield env).tapError(recover(_))

    final def nextEnvelope: ZIO[Any, Throwable, Option[FlowEnvelope[I, T]]] =
      (for {
        cs  <- state.get
        env <- cs.state match {
                 case EndpointStateDetail.Started =>
                   if (cs.inflight.size < config.capacity) {
                     for {
                       env       <- connector.nextEnvelope
                       envWithAck = env.map(e => e.withMeta(AckHandler.key, ackHandler(e)))
                       _         <-
                         ZIO.foreach(envWithAck) { env =>
                           state.update(s => ZIO.succeed(s.copy(inflight = s.inflight :+ env)))
                         }
                     } yield envWithAck
                   } else {
                     ZIO.none
                   }
                 case _                           => ZIO.none
               }
      } yield env).tapError(recover(_))

    def stream: ZIO[Any, IllegalStateException, ZStream[Any, Nothing, FlowEnvelope[I, T]]] = {
      def consumeForEver(buffer: Queue[FlowEnvelope[I, T]]): ZIO[Any, Nothing, Unit] = {
        val part = Stream.repeatEffect(nextEnvelope).collect { case Some(env) => env }.foreach(buffer.offer(_))
        part.catchAll(_ =>
          consumeForEver(buffer)
            .schedule(Schedule.duration(config.recoveryInterval))
            .fork
            .provideLayer(Clock.live) *> ZIO.unit
        )
      }

      ZIO.ifM(streamRef.get.map(_.isDefined))(
        ZIO.fail(new IllegalStateException(s"Stream is already defined for Endpoint [$id]")),
        for {
          q <- zio.Queue.bounded[FlowEnvelope[I, T]](1)
          p <- consumeForEver(q).fork *> getOrCreateStop
          s  = ZStream.repeatEffect(q.take).interruptWhen(p)
          _ <- streamRef.update(_ => Some(s))
        } yield s
      )
    }

    def sink: ZIO[Any, IllegalStateException, ZSink[Any, Nothing, FlowEnvelope[
      I,
      T
    ], FlowEnvelope[
      I,
      T
    ], Unit]] = {

      def produceForEver(buffer: Queue[FlowEnvelope[I, T]]): ZIO[Any, Nothing, Unit] = {
        val part = buffer.take.flatMap(send(_).flatMap(_.ackOrDeny)).forever
        part.catchAll(_ =>
          produceForEver(buffer)
            .schedule(Schedule.duration(config.recoveryInterval))
            .fork
            .provideLayer(Clock.live) *> ZIO.unit
        )
      }

      ZIO.ifM(sinkRef.get.map(_.isDefined))(
        ZIO.fail(new IllegalStateException(s"Sink is already defined for Endpoint [$id]")),
        for {
          p <- getOrCreateStop
          q <- zio.Queue.bounded[FlowEnvelope[I, T]](1)
          s <- produceForEver(q).fork *> ZIO.succeed(
                 ZSink
                   .foreach[Any, Nothing, FlowEnvelope[I, T]](msg => q.offer(msg))
                   .untilOutputM(_ => p.isDone)
                   .map(_ => ())
               )
        } yield s
      )
    }

    private def getOrCreateStop = for {
      _ <- stop.updateSome { case None => Promise.make[Nothing, Boolean].map(Some(_)) }
      p <- stop.get.flatMap {
             _ match {
               case None    => ZIO.fail(new IllegalStateException("Shouldn't happen"))
               case Some(v) => ZIO.succeed(v)
             }
           }
    } yield p

    // To recover, we first disconnect the endpoint and then schedule a state change to "created"
    // Then we can try to reconnect
    private def recover(t: Throwable): ZIO[Any, Throwable, Unit] =
      logger.info(s"Starting recovery for Endpoint [$id] : [${t.getMessage()}]") *>
        state.updateSome {
          case EndpointState(EndpointStateDetail.Started, _) =>
            ZIO.succeed(EndpointState[I, T](EndpointStateDetail.Recovering, Chunk.empty)) <*
              disconnect <* ((reset *> connect)
                .schedule(Schedule.duration(config.recoveryInterval))
                .forkDaemon)
                .provideLayer(Clock.live)
          case EndpointState(EndpointStateDetail.Created, _) =>
            ZIO.succeed(EndpointState[I, T](EndpointStateDetail.Recovering, Chunk.empty))
        }

    private def reset: ZIO[Any, Nothing, Unit] =
      state.updateSome { case EndpointState(EndpointStateDetail.Recovering, _) =>
        ZIO.succeed(EndpointState[I, T](EndpointStateDetail.Created, Chunk.empty))
      }

    private def ackHandler[I, T](env: FlowEnvelope[I, T]) = new AckHandler {
      def removeFromInflight = state.update(s => ZIO.succeed(s.copy(inflight = s.inflight.filter(_.id != env.id))))

      override def ack  = removeFromInflight
      override def deny = removeFromInflight
    }
  }
}
