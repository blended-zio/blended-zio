package blended.zio.streams

import zio._

trait Endpoint[T] {

  /* get a single message from the endpoint and wrap it into a FlowEnvelope[T] */
  def consume[R, E]: ZIO[R, E, FlowEnvelope[T]]

  /* Send a single envelope to the endpoint */
  def send[R, E](env: FlowEnvelope[T]): ZIO[R, E, FlowEnvelope[T]]
}
