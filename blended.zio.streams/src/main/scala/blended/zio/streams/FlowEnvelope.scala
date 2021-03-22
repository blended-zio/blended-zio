package blended.zio.streams

import zio._
import blended.zio.core.Zipped._

final case class FlowEnvelope[+C](
  meta: EnvelopeMetaMap,
  content: C
) { self =>

  /**
   * Simply map the content of the FlowEnvelope by mapping the envelope content and keep the Metadata
   */
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
