package blended.zio.streams

import zio._
import zio.duration._

trait Connector[R, I, T] {

  // Effectfully connect the endpoint to the underlying technology
  def connect: ZIO[R, Throwable, Unit]

  // Effectfully disconnect from the underlying technology
  def disconnect: ZIO[R, Throwable, Unit]

  // Get the next message from the underlying technology
  def nextEnvelope: ZIO[R, Throwable, Option[FlowEnvelope[I, T]]]

  // Send a given envelope to the underlying technology
  def sendEnvelope(f: FlowEnvelope[I, T]): ZIO[R, Throwable, FlowEnvelope[I, T]]
}

sealed class Endpoint[R, I, T](
  val id: String,
  con: Connector[R, I, T],
  cfg: Endpoint.EndpointConfig,
  state: RefM[Endpoint.EndpointState]
) { self =>

  import Endpoint.EndpointState._

  def connect: ZIO[R, Throwable, Unit] = update("connect", doConnect)

  def disconnect: ZIO[R, Throwable, Unit] = update("disconnect", doDisconnect)

  def nextEnvelope: ZIO[R, Throwable, Option[FlowEnvelope[I, T]]] = for {
    cs  <- state.get
    env <- cs match {
             case Connected(_, _) => con.nextEnvelope
             case _               => ZIO.effectTotal(None)
           }
  } yield env

  def send[I1, A](
    env: FlowEnvelope[I1, A]
  )(encode: FlowEnvelope[I1, A] => FlowEnvelope[I, T]): ZIO[R, Throwable, FlowEnvelope[I, T]] =
    ZIO.effectTotal(encode(env)).flatMap(env => con.sendEnvelope(env))

  private val doConnect: Endpoint.StateUpdate[R] = _ match {
    case Created         =>
      for {
        q <- Queue.bounded[FlowEnvelope[I, T]](cfg.capacity)
        _ <- con.connect
      } yield Connected(Chunk.empty, q)
    case Connected(_, _) => state.get
  }

  private val doDisconnect: Endpoint.StateUpdate[R] = _ match {
    case Created                => state.get
    case Connected(inflight, _) =>
      for {
        _ <- ZIO.foreach(inflight)(env => env.deny)
        _ <- con.disconnect
      } yield Created
  }

  private def update(opName: String, f: Endpoint.StateUpdate[R]): ZIO[R, Throwable, Unit] =
    state.update { cs =>
      f.lift(cs) match {
        case None    => ZIO.fail(new IllegalStateException(s"Operation [$opName] undefined in state [$state]"))
        case Some(e) => e
      }
    }
}

object Endpoint {

  type StateUpdate[R] = PartialFunction[Endpoint.EndpointState, ZIO[R, Throwable, Endpoint.EndpointState]]

  def make[R, I, T](id: String, con: Connector[R, I, T], cfg: EndpointConfig): ZIO[R, Nothing, Endpoint[R, I, T]] =
    RefM.make[EndpointState](EndpointState.Created).map(s => new Endpoint[R, I, T](id, con, cfg, s))

  final case class EndpointConfig private (
    capacity: Int,
    pollInterval: Duration,
    recoveryInterval: Duration
  )

  object EndpointConfig {
    val default = EndpointConfig(1, 100.millis, 1.second)

    def make(
      capacity: Int = default.capacity,
      poll: Duration = default.pollInterval,
      recover: Duration = default.recoveryInterval
    ): EndpointConfig =
      EndpointConfig(
        if (capacity <= 0) default.capacity else capacity,
        if (poll.toNanos <= 0) default.pollInterval else poll,
        if (recover.toNanos <= 0) default.recoveryInterval else recover
      )
  }

  sealed trait EndpointState
  object EndpointState {
    case object Created extends EndpointState
    case class Connected[I, T](inflight: Chunk[FlowEnvelope[I, T]], buffer: Queue[FlowEnvelope[I, T]])
      extends EndpointState {
      override def toString() = "Connected"
    }

    case class Recovering(duration: Duration) extends EndpointState
    case object Closed                        extends EndpointState
  }
}
