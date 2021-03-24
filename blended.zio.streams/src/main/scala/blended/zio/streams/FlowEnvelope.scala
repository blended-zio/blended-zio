package blended.zio.streams

import zio._
import blended.zio.core.Zipped._
import java.util.UUID

final case class FlowEnvelope[+C](
  meta: EnvelopeMetaMap,
  content: C
) { self =>

  override def equals(that: Any): Boolean = that match {
    case that: FlowEnvelope[C] => self.content == that.content
    case _                     => false
  }

  override def hashCode() = content.hashCode()

  def map[C1](f: C => C1) = FlowEnvelope[C1](meta, f(content))

  def zip[C1](that: FlowEnvelope[C1])(implicit z: Zippable[C, C1]): FlowEnvelope[z.Out] =
    FlowEnvelope(meta ++ that.meta, z.zip(content, that.content))

  def eraseMeta[V](k: EnvelopeMeta[V]) = FlowEnvelope(meta.eraseMeta(k), content)

  def withMeta[V](k: EnvelopeMeta[V], v: V) = FlowEnvelope(meta.withMeta(k, v), content)

  def header: EnvelopeHeader = meta.get[EnvelopeHeader](EnvelopeHeader.empty)

  def addHeader(newHeader: EnvelopeHeader) =
    FlowEnvelope(meta.update[EnvelopeHeader](EnvelopeHeader.empty, _ ++ newHeader), content)

  def removeHeader(names: String*) =
    FlowEnvelope(meta.update[EnvelopeHeader](EnvelopeHeader.empty, _ -- (names: _*)), content)

  def replaceHeader(newHeader: EnvelopeHeader) =
    FlowEnvelope(meta.overwrite[EnvelopeHeader](EnvelopeHeader.empty, newHeader), content)

  def ackOrDeny = {
    val ah = meta.get[AckHandler](AckHandler.noop)
    ah.ack(self).catchAll(_ => ah.deny(self))
  }
}

object FlowEnvelope {

  /**
   * Create a default envelope with given content of type C and no metadata
   */
  def make[C](c: C) = FlowEnvelope(EnvelopeMetaMap.empty, c)

  /**
   * Run an effect to produce some content and then create an envelope from the result
   */
  def fromEffect[R, E, C](e: ZIO[R, E, C]) = e.map(make)
}

object EnvelopeId {

  type EnvelopeIdService = Has[Service]

  def default: ZLayer[Any, Nothing, EnvelopeIdService] =
    ZLayer.fromEffect(ZIO.effectTotal(new DefaultEnvelopeIdService))

  trait Service {
    def nextId: ZIO[Any, Nothing, String]
  }

  private class DefaultEnvelopeIdService extends Service {
    override def nextId: ZIO[Any, Nothing, String] = ZIO.effectTotal(UUID.randomUUID().toString())
  }
}
