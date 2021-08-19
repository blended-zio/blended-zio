package blended.zio.streams

import zio._

trait AckHandler {
  def ack: ZIO[Any, Throwable, Unit]
  def deny: ZIO[Any, Nothing, Unit]
}

object AckHandler {

  val noop = new AckHandler {
    override def ack  = ZIO.unit
    override def deny = ZIO.unit
  }

  val key: EnvelopeMeta[AckHandler] =
    EnvelopeMeta[AckHandler](
      "ackHandler",
      noop,
      (ah1: AckHandler, ah2: AckHandler) =>
        new AckHandler {
          override def ack  = ah1.ack.flatMap(_ => ah2.ack)
          override def deny = ah1.deny.flatMap(_ => ah2.deny)
        }
    )
}
