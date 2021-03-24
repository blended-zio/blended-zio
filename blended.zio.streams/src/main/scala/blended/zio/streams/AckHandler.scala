package blended.zio.streams

import scala.language.implicitConversions

import zio._

trait AckHandler {
  def ack(env: FlowEnvelope[_]): ZIO[Any, Throwable, Unit]
  def deny(env: FlowEnvelope[_]): ZIO[Any, Nothing, Unit]
}

object AckHandler {

  val noop = new AckHandler {
    override def ack(env: FlowEnvelope[_])  = ZIO.unit
    override def deny(env: FlowEnvelope[_]) = ZIO.unit
  }

  implicit def toEnvelopeMeta(ah: AckHandler): EnvelopeMeta[AckHandler] =
    EnvelopeMeta[AckHandler](
      "ackHandler",
      ah,
      (ah1: AckHandler, ah2: AckHandler) =>
        new AckHandler {
          override def ack(env: FlowEnvelope[_])  = ah1.ack(env).flatMap(_ => ah2.ack(env))
          override def deny(env: FlowEnvelope[_]) = ah1.deny(env).flatMap(_ => ah2.deny(env))
        }
    )
}
