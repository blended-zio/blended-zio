package blended.zio.jmx

import javax.management.ObjectName
import javax.management.openmbean.{ CompositeData, TabularData }

import scala.jdk.CollectionConverters._
import zio._

object JmxAttributeCompanion {

  def make(v: Any): ZIO[Any, IllegalArgumentException, AttributeValue[_]] =
    Option(v) match {
      case None    => ZIO.effectTotal(UnitAttributeValue())
      case Some(o) =>
        o match {
          // doctag<simple>
          case _: Unit                  => ZIO.effectTotal(UnitAttributeValue())
          case s: String                => ZIO.effectTotal(StringAttributeValue(s))
          case i: java.lang.Integer     => ZIO.effectTotal(IntAttributeValue(i))
          case l: java.lang.Long        => ZIO.effectTotal(LongAttributeValue(l))
          case b: java.lang.Boolean     => ZIO.effectTotal(BooleanAttributeValue(b))
          case b: java.lang.Byte        => ZIO.effectTotal(ByteAttributeValue(b))
          case s: java.lang.Short       => ZIO.effectTotal(ShortAttributeValue(s))
          case f: java.lang.Float       => ZIO.effectTotal(FloatAttributeValue(f))
          case d: java.lang.Double      => ZIO.effectTotal(DoubleAttributeValue(d))
          case bi: java.math.BigInteger => ZIO.effectTotal(BigIntegerAtrributeValue(bi))
          case bd: java.math.BigDecimal => ZIO.effectTotal(BigDecimalAtrributeValue(bd))
          // end:doctag<simple>

          case n: ObjectName => ZIO.effectTotal(ObjectNameAttributeValue(JmxObjectName.fromObjectName(n)))

          case al: Array[Long]    => ZIO.effectTotal(ListAttributeValue(al.map(LongAttributeValue.apply).toList))
          case ab: Array[Boolean] =>
            ZIO.effectTotal(ListAttributeValue.apply(ab.map(BooleanAttributeValue.apply).toList))
          case ab: Array[Byte]    => ZIO.effectTotal(ListAttributeValue.apply(ab.map(ByteAttributeValue.apply).toList))
          case ac: Array[Char]    => ZIO.effectTotal(ListAttributeValue.apply(ac.map(CharAttributeValue.apply).toList))
          case ad: Array[Double]  => ZIO.effectTotal(ListAttributeValue.apply(ad.map(DoubleAttributeValue.apply).toList))
          case af: Array[Float]   => ZIO.effectTotal(ListAttributeValue.apply(af.map(FloatAttributeValue.apply).toList))
          case ai: Array[Int]     => ZIO.effectTotal(ListAttributeValue.apply(ai.map(IntAttributeValue.apply).toList))
          case as: Array[Short]   => ZIO.effectTotal(ListAttributeValue.apply(as.map(ShortAttributeValue.apply).toList))

          case a: Array[Any]  =>
            for {
              values <- ZIO.collectPar(a.toList)(v => make(v).mapError(t => Option(t)))
            } yield (ListAttributeValue(values))

          // doctag<tabdata>
          case t: TabularData =>
            for {
              values <- ZIO.collectPar(t.values().asScala)(v => make(v).mapError(t => Option(t)))
            } yield TabularAttributeValue(values.toList)

          case cd: CompositeData =>
            for {
              attrs <- ZIO.collectPar(cd.getCompositeType().keySet().asScala.toList) { k =>
                         ZIO.tupled(ZIO.succeed(k), make(cd.get(k))).mapError(t => Option(t))
                       }
            } yield CompositeAttributeValue(attrs.toMap)
          // end:doctag<tabdata>

          case _ => ZIO.fail(new IllegalArgumentException(s"Unsupported Attribute type [${o.getClass.getName}]"))
        }
    }
}
