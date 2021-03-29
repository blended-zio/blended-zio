package blended.zio.streams

import zio._

sealed trait EndpointStateData

object EndpointStateData {
  case object Created    extends EndpointStateData
  case object Working    extends EndpointStateData
  case object Recovering extends EndpointStateData
  case object Closed     extends EndpointStateData
}

final case class EndpointState(
  id: String,
  state: EndpointStateData,
  inflight: Map[String, FlowEnvelope[_, _]]
)

trait Endpoint[I, T] {

  /**
   * The unique key for this particular endpoint
   */
  val id: String

  /**
   * Initialise the underlying endpoint
   */
  def init[R, E, Unit]: ZIO[R, E, Unit]

  /**
   * Definitely close the underlying Endpoint
   */
  def close[R, E, Unit]: ZIO[R, E, Unit]

  def recover[R, E, Unit]: ZIO[R, E, Unit]

  /**
   * Get a single message from the underlying endpoint and wrap it into a FlowEnvelope.
   * The FlowEnvelope will be enriched with meta data specific to the endpoint.
   */
  def poll: ZIO[Any, Throwable, Option[FlowEnvelope[I, T]]]

  /**
   * Send a given FlowEnvelope via the Endpoint. Metadata within the envelope may be
   * taken into account to further specify relevant send parameters.
   */
  def send(env: FlowEnvelope[I, T]): ZIO[Any, Throwable, FlowEnvelope[I, T]]
}

sealed private class EndpointRunner {}
