package blended.zio.jmx

import java.lang.management.ManagementFactory
import javax.management.{ MBeanAttributeInfo, MBeanServer, ObjectName }

import scala.collection.mutable
import scala.util.Try

import zio._
import zio.logging._

object MBeanServerFacade {

  type MBeanServerFacade = Has[Service]

  // doctag<service>
  trait Service {

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
  val live: ZLayer[Logging, Nothing, MBeanServerFacade] = ZLayer.fromFunction(log =>
    new Service {
      private val impl: JvmMBeanServerFacade = new JvmMBeanServerFacade(ManagementFactory.getPlatformMBeanServer)

      override def mbeanInfo(objName: JmxObjectName): ZIO[Any, Throwable, JmxBeanInfo] =
        impl.mbeanInfo(objName).provide(log)

      override def mbeanNames(objName: Option[JmxObjectName]): ZIO[Any, Throwable, List[JmxObjectName]] =
        impl.mbeanNames(objName).provide(log)
    }
  )
  // end:doctag<zlayer>
}

final class JvmMBeanServerFacade(svr: MBeanServer) {

  // doctag<names>
  def mbeanNames(objName: Option[JmxObjectName]): ZIO[Logging, Throwable, List[JmxObjectName]] = for {
    pattern <- optionalPattern(objName)
    _       <- doLog(LogLevel.Info)(s"Querying object names with [$pattern]")
    names   <- queryNames(pattern)
    res      = names.map(JmxObjectName.fromObjectName)
  } yield res
  // end:doctag<names>

  // doctag<info>
  def mbeanInfo(objName: JmxObjectName): ZIO[Logging, Throwable, JmxBeanInfo] = for {
    on           <- ZIO.effect(new ObjectName(objName.objectName))
    _            <- doLog(LogLevel.Info)(s"Getting MBeanInfo [$objName]")
    info          = svr.getMBeanInfo(on)
    readableAttrs = info.getAttributes.filter(_.isReadable())
    mapped       <- mapAllAttributes(on, readableAttrs)
    result        = JmxBeanInfo(objName, mapped)
  } yield result

  private def doLog(level: LogLevel)(msg: => String): ZIO[Logging, Nothing, Unit] = for {
    _ <- log.locally(LogAnnotation.Name(getClass.getName :: Nil)) {
           log(level)(msg)
         }
  } yield ()
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
