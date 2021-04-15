package blended.zio.jmx

import scala.collection.mutable
import javax.management.ObjectName
import java.util.function.BiConsumer
import zio._

class InvalidObjectNameFormatException(name: String) extends Exception(s"Value [${name}] is not a valid object name")

object JmxObjectName {

  val defaultDomain = "blended"

  def fromObjectName(on: ObjectName): JmxObjectName = {

    val props = mutable.Map.empty[String, String]

    on.getKeyPropertyList()
      .forEach(new BiConsumer[String, String]() {
        override def accept(k: String, v: String) = {
          props.put(k, v)
          ()
        }
      })

    JmxObjectName(on.getDomain, props.toMap)
  }

  private def parseMapEffect(s: String): ZIO[Any, Throwable, Map[String, String]] = for {
    parts <- ZIO.effectTotal(s.split(","))
    props <- ZIO.effect {
               parts.map { p =>
                 val keyVal: Array[String] = p.split("=")

                 if (keyVal.length == 2 && keyVal.forall(_.trim.length > 0)) {
                   keyVal(0).trim -> keyVal(1).trim
                 } else {
                   throw new Exception("Unable to parse map from String")
                 }
               }
             }
  } yield (props.toMap)

  def make(s: String): ZIO[Any, InvalidObjectNameFormatException, JmxObjectName] = (for {
    name  <- ZIO.fromOption(Option(s))
    parts  = name.split(":")
    props <- ZIO.ifM(ZIO.effectTotal(parts.length == 2))(
               parseMapEffect(parts(1)),
               ZIO.fail(new InvalidObjectNameFormatException(s))
             )
  } yield JmxObjectName(parts(0), props)).mapError(_ => new InvalidObjectNameFormatException(s))

}

case class JmxObjectName(
  domain: String = JmxObjectName.defaultDomain,
  properties: Map[String, String]
) {

  override def equals(obj: Any): Boolean = obj match {
    case on: JmxObjectName => on.objectName.equals(objectName)
    case _                 => false
  }

  override def hashCode(): Int = objectName.hashCode

  def sortedProps: List[String] = properties.view
    .mapValues(_.replaceAll(":", "/"))
    .toList
    .sorted
    .map { case (k, v) => s"$k=$v" }

  def objectName: String        = s"$domain:${sortedProps.mkString(",")}"

  override val toString: String = s"${getClass.getSimpleName}($objectName)"

  def isAncestor(other: JmxObjectName): ZIO[Any, Nothing, Boolean] = ZIO.effectTotal {
    domain.equals(other.domain) &&
    properties.size < other.properties.size &&
    properties.forall { case (k, v) => other.properties(k).equals(v) }
  }

  def isParent(other: JmxObjectName): ZIO[Any, Nothing, Boolean] = for {
    a <- isAncestor(other)
    p  = a && (properties.size == other.properties.size - 1)
  } yield (p)

  def differingKeys(other: JmxObjectName): ZIO[Any, Nothing, List[String]] =
    ZIO.ifM(ZIO.effectTotal(properties.size < other.properties.size))(
      other.differingKeys(this),
      ZIO.effectTotal {
        properties.filter { case (k, v) =>
          !other.properties.contains(k) || !other.properties(k).equals(v)
        }.keys.toList
      }
    )
}
