package blended.zio.jmx

// doctag<beaninfo>
final case class JmxBeanInfo(
  objName: JmxObjectName,
  attributes: CompositeAttributeValue
)
// end:doctag<beaninfo>

final case class JmxAttribute[T <: AnyRef](
  name: String,
  v: AttributeValue[T]
)

sealed trait AttributeValue[T] {
  def value: T
  override def toString: String = value.toString
  override def equals(obj: Any): Boolean
  override def hashCode(): Int  = value.hashCode()
}

case class StringAttributeValue(override val value: String) extends AttributeValue[String] {
  override def toString: String = "\"" + super.toString + "\""
}

final case class UnitAttributeValue(override val value: Unit = ())  extends AttributeValue[Unit]
final case class IntAttributeValue(override val value: Int)         extends AttributeValue[Int]
final case class LongAttributeValue(override val value: Long)       extends AttributeValue[Long]
final case class BooleanAttributeValue(override val value: Boolean) extends AttributeValue[Boolean]
final case class ByteAttributeValue(override val value: Byte)       extends AttributeValue[Byte]
final case class ShortAttributeValue(override val value: Short)     extends AttributeValue[Short]
final case class FloatAttributeValue(override val value: Float)     extends AttributeValue[Float]
final case class DoubleAttributeValue(override val value: Double)   extends AttributeValue[Double]
final case class CharAttributeValue(override val value: Char)       extends AttributeValue[Char]

final case class BigIntegerAtrributeValue(override val value: BigInt)     extends AttributeValue[BigInt]
final case class BigDecimalAtrributeValue(override val value: BigDecimal) extends AttributeValue[BigDecimal]

final case class ObjectNameAttributeValue(override val value: JmxObjectName) extends AttributeValue[JmxObjectName]

final case class ListAttributeValue(override val value: List[AttributeValue[_]])
  extends AttributeValue[List[AttributeValue[_]]] {
  override def toString: String = value.mkString("[", ",", "]")
}

final case class CompositeAttributeValue(override val value: Map[String, AttributeValue[_]])
  extends AttributeValue[Map[String, AttributeValue[_]]]
final case class TabularAttributeValue(override val value: List[AttributeValue[_]])
  extends AttributeValue[List[AttributeValue[_]]]
