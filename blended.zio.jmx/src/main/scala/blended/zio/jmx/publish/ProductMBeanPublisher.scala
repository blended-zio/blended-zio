package blended.zio.jmx.publish

import java.lang.management.ManagementFactory
import javax.management._

import scala.util.Try

import zio._
import zio.logging._
import zio.stm._

import blended.zio.jmx.JmxObjectName
import blended.zio.jmx.publish.Nameable._

object ProductMBeanPublisher {

  type ProductMBeanPublisher = Has[Service]

  sealed trait MBeanPublishException
  class JmxException(inner: Throwable) extends Exception(inner) with MBeanPublishException

  class IncompatibleJmxUpdateException(
    val newClass: Class[_],
    val oldClass: Class[_]
  ) extends Exception(
      s"Tried to update MBean of Type [${oldClass.getName}] with instance of [${newClass.getName}]"
    )
    with MBeanPublishException {

    override def equals(obj: Any): Boolean = obj match {
      case e: IncompatibleJmxUpdateException => newClass.equals(e.newClass) && oldClass.equals(e.oldClass)
      case _                                 => false
    }

    override def hashCode(): Int = getMessage.hashCode
  }

  class InstanceAlreadyExistsException[T <: Product](val v: T, objName: JmxObjectName)
    extends Exception(s"A MBean with name [${objName}] already exists for [$v]")
    with MBeanPublishException { self =>

    override def equals(obj: Any): Boolean = obj match {
      case iae: InstanceAlreadyExistsException[_] if self.v.canEqual(iae.v) => iae.v == self.v
      case _                                                                => false
    }

    override def hashCode(): Int = getMessage().hashCode
  }

  private[publish] class OpenProductMBean private[publish] (
    val beanClass: Class[_],
    var inner: DynamicMBean
  ) extends DynamicMBean {

    override def toString: String = s"${getClass.getSimpleName}(${beanClass.getName})"

    def update(
      updClass: Class[_],
      updValue: DynamicMBean
    ): Try[Unit] = Try {
      if (!updClass.equals(beanClass)) {
        throw new IncompatibleJmxUpdateException(updClass, beanClass)
      } else {
        inner = updValue
      }
    }

    override def getAttribute(attribute: String): Object                                             = inner.getAttribute(attribute)
    override def getAttributes(attributes: Array[String]): AttributeList                             = inner.getAttributes(attributes)
    override def getMBeanInfo(): MBeanInfo                                                           = inner.getMBeanInfo()
    override def invoke(actionName: String, params: Array[Object], signature: Array[String]): Object =
      inner.invoke(actionName, params, signature)
    override def setAttribute(attribute: Attribute): Unit                                            = inner.setAttribute(attribute)
    override def setAttributes(attributes: AttributeList): AttributeList                             = inner.setAttributes(attributes)
  }

  // doctag<service>
  trait Service {

    /**
     * Retrieve the list of object names that are currently registered by this service.
     */
    def managedNames: ZIO[Any, Nothing, List[String]]

    /**
     * Create or update the MBean within JMX with the <code>DynamicMBean</code> representation of the given case class instance.
     */
    def updateMBean[T <: Product](
      v: T
    )(implicit f: T => Nameable[T]): ZIO[Any, MBeanPublishException, Unit]

    /**
     * Remove the registration from JMX for the object name derived from the given case class.
     */
    def removeMBean[T <: Product](v: T)(implicit f: T => Nameable[T]): ZIO[Any, MBeanPublishException, Unit]
  }
  // end:doctag<service>

  object Service {

    private val svcRef: Ref[Option[Service]] =
      Runtime.default.unsafeRun(Ref.make[Option[Service]](None))

    val live: ZIO[Logging, Nothing, Service] = for {
      ref <- svcRef.get
      svc <- ZIO.fromOption(ref).orElse(createService)
    } yield svc

    private def createService: ZIO[Logging, Nothing, Service] = for {
      log <- ZIO.service[Logger[String]]
      pub <- ConcurrentMBeanPublisher.make()
      svc  = new Service {
               override def managedNames: ZIO[Any, Nothing, List[String]] = pub.managedNames
               override def updateMBean[T <: Product](v: T)(implicit
                 f: T => Nameable[T]
               ): ZIO[Any, MBeanPublishException, Unit]                   =
                 pub.updateMBean(v).provide(Has(log))
               override def removeMBean[T <: Product](v: T)(implicit
                 f: T => Nameable[T]
               ): ZIO[Any, MBeanPublishException, Unit]                   =
                 pub.removeMBean(v).provide(Has(log))
             }
      _   <- svcRef.set(Some(svc))
      _   <- log.info(s"Created ProductMBeanPublisher")
    } yield svc
  }

  val live: ZLayer[Logging, Nothing, ProductMBeanPublisher] = ZLayer.fromEffect(Service.live)
}

import ProductMBeanPublisher.OpenProductMBean

final class ConcurrentMBeanPublisher private (
  beans: TMap[String, OpenProductMBean]
) { self =>

  import ProductMBeanPublisher._

  private val svr: MBeanServer        = ManagementFactory.getPlatformMBeanServer
  private val mapper: OpenMBeanMapper = new OpenMBeanMapper()

  def managedNames: ZIO[Any, Nothing, List[String]] = self.beans.keys.commit

  // doctag<methods>
  def updateMBean[T <: Product](
    v: T
  )(implicit f: T => Nameable[T]): ZIO[Logging, MBeanPublishException, Unit] =
    createOrUpdate(v).commit.mapError {
      case mbe: MBeanPublishException                         => mbe
      case _: javax.management.InstanceAlreadyExistsException => new InstanceAlreadyExistsException[T](v, objectName(v))
      case t                                                  => new JmxException(t)
    } <* log.debug(s"updated MBean with name [${objectName(v)}}] to [$v]")

  def removeMBean[T <: Product](v: T)(implicit f: T => Nameable[T]): ZIO[Logging, MBeanPublishException, Unit] =
    self.beans
      .get(objectName(v).objectName)
      .flatMap {
        case Some(_) =>
          STM.fromTry(Try {
            try svr.unregisterMBean(new ObjectName(objectName(v).objectName))
            catch {
              case _: InstanceNotFoundException => // swallow that exception as it may occur in STM retries
            }
          }) >>> self.beans.delete(objectName(v).objectName)
        case None    => STM.unit
      }
      .commit
      .mapError {
        case mbe: MBeanPublishException => mbe
        case t                          => new JmxException(t)
      } <* log.debug(s"Removed MBean with name [${objectName(v)}]")
  // end:doctag<methods>

  // doctag<helpers>
  private def updateMBean[T <: Product](old: OpenProductMBean, bean: T): STM[Throwable, Unit] =
    STM.ifM(STM.succeed(old.beanClass.equals(bean.getClass)))(
      STM.fromTry(Try {
        val mapped = mapper.mapProduct(bean)
        old.update(bean.getClass, mapped).get
      }),
      STM.fail(new IncompatibleJmxUpdateException(bean.getClass, old.beanClass))
    )

  private def createMBean[T <: Product](bean: T)(implicit f: T => Nameable[T]): STM[Throwable, Unit] =
    STM
      .fromTry(Try {
        val on: ObjectName = new ObjectName(objectName(bean).objectName)

        try svr.unregisterMBean(on)
        catch {
          case _: InstanceNotFoundException => // swallow that exception
        }

        val mapped = mapper.mapProduct(bean)
        val b      = new OpenProductMBean(bean.getClass, mapped)
        svr.registerMBean(b, new ObjectName(objectName(bean).objectName))
        b
      })
      .flatMap { b =>
        self.beans.put(objectName(bean).objectName, b)
      }

  private def createOrUpdate[T <: Product](bean: T)(implicit f: T => Nameable[T]): STM[Throwable, Unit] =
    self.beans.get(objectName(bean).objectName).flatMap {
      case Some(e) => updateMBean(e, bean)
      case None    => createMBean(bean)
    }
  // end:doctag<helpers>

}

object ConcurrentMBeanPublisher {
  def make(): ZIO[Any, Nothing, ConcurrentMBeanPublisher] = (for {
    b <- TMap.empty[String, OpenProductMBean]
  } yield new ConcurrentMBeanPublisher(b)).commit
}
