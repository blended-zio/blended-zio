package blended.zio.streams

import zio._
import blended.zio.core.Zipped._
import java.util.UUID

final case class FlowEnvelope[+I, +C](
  id: I,
  meta: EnvelopeMetaMap,
  content: C
) { self =>

  override def equals(that: Any): Boolean = that match {
    case that: FlowEnvelope[I, C] => self.id == that.id && self.content == that.content
    case _                        => false
  }

  override def hashCode() = content.hashCode()

  def mapContent[C1](f: C => C1) = FlowEnvelope[I, C1](id, meta, f(content))

  def mapId[I1](f: I => I1) = FlowEnvelope[I1, C](f(id), meta, content)

  def map[I1, C1](fi: I => I1)(fc: C => C1) = FlowEnvelope[I1, C1](fi(id), meta, fc(content))

  def zip[I1, C1](
    that: FlowEnvelope[I1, C1]
  )(implicit i: Zippable[I, I1], z: Zippable[C, C1]): FlowEnvelope[i.Out, z.Out] =
    FlowEnvelope(i.zip(self.id, that.id), meta ++ that.meta, z.zip(content, that.content))

  def eraseMeta[V](k: EnvelopeMeta[V]) = FlowEnvelope(id, meta.eraseMeta(k), content)

  def withMeta[V](k: EnvelopeMeta[V], v: V) = FlowEnvelope(id, meta.withMeta(k, v), content)

  def header: EnvelopeHeader = meta.get[EnvelopeHeader](EnvelopeHeader.key)

  def addHeader(newHeader: EnvelopeHeader) =
    FlowEnvelope(id, meta.update[EnvelopeHeader](EnvelopeHeader.key, _ ++ newHeader), content)

  def removeHeader(names: String*) =
    FlowEnvelope(id, meta.update[EnvelopeHeader](EnvelopeHeader.key, _ -- (names: _*)), content)

  def replaceHeader(newHeader: EnvelopeHeader) =
    FlowEnvelope(id, meta.overwrite[EnvelopeHeader](EnvelopeHeader.key, newHeader), content)

  def ackOrDeny = {
    val ah = meta.get[AckHandler](AckHandler.key)
    ah.ack(self).catchAll(_ => ah.deny(self))
  }

  def deny = {
    val ah = meta.get[AckHandler](AckHandler.key)
    ah.deny(self)
  }
}

object FlowEnvelope {

  def make[I, C](i: I, c: C): FlowEnvelope[I, C] = FlowEnvelope(i, EnvelopeMetaMap.empty, c)
  def make[C](c: C): FlowEnvelope[String, C]     = make(UUID.randomUUID().toString(), c)

  /**
   * Run an effect to produce some content and then create an envelope from the result
   */
  def fromEffect[R, E, C](e: ZIO[R, E, C]) = for {
    c  <- e
    env = make(c)
  } yield env
}
