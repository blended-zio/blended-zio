package blended.zio.jmx

import java.lang.management.ManagementFactory
import javax.management.{ MBeanAttributeInfo, MBeanServer, ObjectName }

import scala.collection.mutable
import scala.util.Try

import zio._
import zio.logging._

object MBeanServerFacade {

  // doctag<service>
  trait MBeanServerSvc {

    /**
     * Retrieve the information for a MBean by it's object name. Any JMX related information will be
     * passed to the error channel without modification. If successful, A [[JmxBeanInfo]] will be
     * returned.
     */
    def mbeanInfo(objName: JmxObjectName): ZIO[Any, Throwable, JmxBeanInfo]

    /**
     * Retrieve the list of all MBeanNames known to the underlying MBean Server.
     * Any JMX exception that might occur will be passed onwards in the error
     * channel. If successful, a list of [[JmxObjectName]]s will be returned.
     */
    def allMbeanNames(): ZIO[Any, Throwable, List[JmxObjectName]] = mbeanNames(None)

    /**
     * Retrieve the list of all object names know to the underlying MBean Server.
     * Any JMX exception that might occur will be passed onwards in the error
     * channel. If successful, a list of [[JmxObjectName]]s will be returned.
     * @param objName If non-empty, the result will contain all object names that
     *                are in the same JMX domain and have all properties set within
     *                the parameter as additional name properties.
     *                If empty, no filtering will be applied.
     */
    def mbeanNames(objName: Option[JmxObjectName]): ZIO[Any, Throwable, List[JmxObjectName]]
  }
  // end:doctag<service>

  // doctag<zlayer>
  val live =
    (ZIO
      .service[Logger[String]]
      .map { logger =>
        new MBeanServerSvc {
          private val impl: JvmMBeanServerFacade =
            new JvmMBeanServerFacade(ManagementFactory.getPlatformMBeanServer, logger)

          override def mbeanInfo(objName: JmxObjectName): ZIO[Any, Throwable, JmxBeanInfo] =
            impl.mbeanInfo(objName)

          override def mbeanNames(objName: Option[JmxObjectName]): ZIO[Any, Throwable, List[JmxObjectName]] =
            impl.mbeanNames(objName)
        }
      })
      .toLayer
  // end:doctag<zlayer>
}

final class JvmMBeanServerFacade(svr: MBeanServer, logger: Logger[String]) {

  // doctag<names>
  def mbeanNames(objName: Option[JmxObjectName]) = for {
    pattern <- optionalPattern(objName)
    _       <- logger.info(s"Querying object names with [$pattern]")
    names   <- queryNames(pattern)
    res      = names.map(JmxObjectName.fromObjectName)
  } yield res
  // end:doctag<names>

  // doctag<info>
  def mbeanInfo(objName: JmxObjectName) = for {
    on           <- ZIO.effect(new ObjectName(objName.objectName))
    _            <- logger.info(s"Getting MBeanInfo [$objName]")
    info          = svr.getMBeanInfo(on)
    readableAttrs = info.getAttributes.filter(_.isReadable())
    mapped       <- mapAllAttributes(on, readableAttrs)
    result        = JmxBeanInfo(objName, mapped)
  } yield result
  // end:doctag<info>

  // Helper method to map a list of JMX Attributes into their JmxAttributes
  private def mapAllAttributes(
    on: ObjectName,
    attrs: Iterable[MBeanAttributeInfo]
  ): ZIO[Any, Throwable, CompositeAttributeValue] = for {
    attrs   <- ZIO.collect(attrs)(a => mapAttribute(on, a))
    combined = attrs.foldLeft(Map.empty[String, AttributeValue[_]]) { case (cur, v) => cur ++ v }
    comp     = CompositeAttributeValue(combined)
  } yield comp

  // doctag<attribute>
  // Helper method to create a single JmxAttribute
  private def mapAttribute(
    on: ObjectName,
    info: MBeanAttributeInfo
  ): ZIO[Any, Nothing, Map[String, AttributeValue[_]]] = (for {
    attr <- ZIO.fromTry(Try {
              svr.getAttribute(on, info.getName)
            })
    av   <- JmxAttributeCompanion.make(attr)
  } yield (Map(info.getName -> av))).orElse(ZIO.succeed(Map.empty[String, AttributeValue[_]]))
  // end:doctag<attribute>

  // doctag<helper>
  // Helper method to query the MBean Server for existing MBean names. If a pattern is given this will be used
  // as a search pattern, otherwise all names will be returned
  private def queryNames(pattern: Option[ObjectName]): ZIO[Any, Throwable, List[ObjectName]] = ZIO.effect {
    val names: mutable.ListBuffer[ObjectName] = mutable.ListBuffer.empty[ObjectName]
    svr.queryNames(pattern.orNull, null).forEach(n => names.append(n))
    names.toList
  }

  // Helper method create an optional pattern
  private def optionalPattern(name: Option[JmxObjectName]): ZIO[Any, Throwable, Option[ObjectName]] = name match {
    case None    => ZIO.none
    case Some(n) => toPattern(n).map(Some(_))
  }

  // helper method to create a JMX search pattern from a given object name
  private def toPattern(name: JmxObjectName): ZIO[Any, Throwable, ObjectName] =
    ZIO.effect {
      val props = name.sortedProps
      new ObjectName(s"${name.domain}:${props.mkString(",")},*")
    }

  // end:doctag<helper>

}
