package blended.zio.streams

import zio.Tag

final case class EnvelopeMeta[V] private (
  identifier: String,
  initial: V,
  combine: (V, V) => V,
  private val tag: String
) {

  override def equals(that: Any): Boolean = that match {
    case that: EnvelopeMeta[_] => (identifier, tag) == ((that.identifier, that.tag))
    case _                     => false
  }

  override def hashCode = (identifier + tag).hashCode()

  override def toString = s"($identifier,$tag)"
}

object EnvelopeMeta {

  def apply[V](id: String, init: V, comb: (V, V) => V)(implicit t: Tag[V]): EnvelopeMeta[V] =
    EnvelopeMeta[V](
      identifier = id,
      initial = init,
      combine = comb,
      tag = t.tag.longName
    )
}

final class EnvelopeMetaMap private (private val map: Map[EnvelopeMeta[Any], AnyRef]) { self =>

  def ++(that: EnvelopeMetaMap): EnvelopeMetaMap = new EnvelopeMetaMap(
    (self.map.toVector ++ that.map.toVector).foldLeft[Map[EnvelopeMeta[Any], AnyRef]](Map()) { case (acc, (k, v)) =>
      acc + (k -> acc.get(k).fold(v)(k.combine(_, v).asInstanceOf[AnyRef]))
    }
  )

  def eraseMeta[V](k: EnvelopeMeta[V]): EnvelopeMetaMap = new EnvelopeMetaMap(map - k.asInstanceOf[EnvelopeMeta[Any]])

  def withMeta[V](k: EnvelopeMeta[V], value: V): EnvelopeMetaMap = update(k, k.combine(_, value))

  def get[V](k: EnvelopeMeta[V]): V = map.get(k.asInstanceOf[EnvelopeMeta[Any]]).fold(k.initial)(_.asInstanceOf[V])

  def overwrite[V](k: EnvelopeMeta[V], value: V): EnvelopeMetaMap =
    new EnvelopeMetaMap(map + (k.asInstanceOf[EnvelopeMeta[Any]] -> value.asInstanceOf[AnyRef]))

  def update[V](k: EnvelopeMeta[V], f: V => V): EnvelopeMetaMap   = overwrite(k, f(get(k)))

  override def toString = map.map { case (k, v) => s"$k -> $v" }.mkString(",")
}

object EnvelopeMetaMap {
  def empty = new EnvelopeMetaMap(Map.empty)
}
