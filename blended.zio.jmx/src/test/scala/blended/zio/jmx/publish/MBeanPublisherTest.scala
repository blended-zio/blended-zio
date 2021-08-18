package blended.zio.jmx.publish

import javax.management.InstanceNotFoundException

import scala.annotation.nowarn

import zio._
import zio.duration._
import zio.logging.slf4j._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

import blended.zio.jmx.publish.ProductMBeanPublisher.IncompatibleJmxUpdateException
import blended.zio.jmx.{ JmxObjectName, MBeanServerFacade }

object MBeanPublisherTest extends DefaultRunnableSpec {

  import Nameable._

  // doctag<layer>
  private val logSlf4j = Slf4jLogger.make((_, message) => message)

  private val jmxLayer =
    (logSlf4j >>> ProductMBeanPublisher.live) ++ (logSlf4j >>> MBeanServerFacade.live)
  // end:doctag<layer>

  override def spec = suite("The MBeanPublisher should")(
    simplePublish,
    simpleUpdate,
    incompatible,
    remove
  ).provideCustomLayer(jmxLayer) @@ timed @@ timeoutWarning(1.minute) @@ parallel

  // doctag<simple>
  private val simplePublish = testM("publish a simple case class")(for {
    pub  <- ZIO.service[ProductMBeanPublisher.MBeanPublisherSvc]
    fac  <- ZIO.service[MBeanServerFacade.MBeanServerSvc]
    cc   <- ZIO.succeed(Simple("test1", 0, "Hello Jmx"))
    info <- pub.updateMBean(cc) >>> fac.mbeanInfo(objectName(cc))
  } yield {
    val keys = info.attributes.value.keys.toList
    assert(keys)(contains("counter")) &&
    assert(keys)(contains("message")) &&
    assert(keys)(hasSize(equalTo(3))) &&
    assert(info.attributes.value("counter").value.asInstanceOf[Int])(equalTo(0))
  })
  // end:doctag<simple>

  private val simpleUpdate = testM("publish updates for a simple case class")(for {
    pub  <- ZIO.service[ProductMBeanPublisher.MBeanPublisherSvc]
    fac  <- ZIO.service[MBeanServerFacade.MBeanServerSvc]
    cc   <- ZIO.succeed(Simple("test2", 0, "Hello Jmx"))
    _    <- pub.updateMBean(cc)
    _    <- pub.updateMBean(cc.copy(counter = 100, message = "Hello Update"))
    info <- fac.mbeanInfo(objectName(cc))
  } yield {
    val keys = info.attributes.value.keys.toList
    assert(keys)(contains("counter")) &&
    assert(keys)(contains("message")) &&
    assert(keys)(hasSize(equalTo(3))) &&
    assert(info.attributes.value("counter").value.asInstanceOf[Int])(equalTo(100))
  })

  private val incompatible = testM("deny incompatible updates")(for {
    pub <- ZIO.service[ProductMBeanPublisher.MBeanPublisherSvc]
    s1  <- ZIO.succeed(Simple("test3", 0, "Hello Jmx"))
    s2  <- ZIO.succeed(Simple2("test3", 0L))
    _   <- pub.updateMBean(s1)
    r   <- pub.updateMBean(s2).run
  } yield assert(r)(fails(equalTo(new IncompatibleJmxUpdateException(classOf[Simple2], classOf[Simple])))))

  private val remove = testM("allow to remove a registered MBean")(for {
    pub <- ZIO.service[ProductMBeanPublisher.MBeanPublisherSvc]
    cc  <- ZIO.succeed(Simple("test4", 0, "Hello Jmx"))
    fac <- ZIO.service[MBeanServerFacade.MBeanServerSvc]
    _   <- pub.updateMBean(cc)
    _   <- pub.removeMBean(cc)
    r   <- fac.mbeanInfo(objectName(cc)).run
  } yield assert(r)(failsCause(assertion("type")() { c =>
    c.dieOption.isDefined && c.dieOption.head.isInstanceOf[InstanceNotFoundException]
  })))
}

object Simple {
  @nowarn
  implicit def toNameable(v: Simple): Nameable[Simple] = new Nameable[Simple] {
    override def objectName(x: Simple): JmxObjectName =
      JmxObjectName("blended", Map("type" -> "simple", "name" -> x.name))
  }
}

final case class Simple(
  name: String,
  counter: Int,
  message: String
)

object Simple2 {
  @nowarn
  implicit def toNameable(v: Simple2): Nameable[Simple2] = new Nameable[Simple2] {
    override def objectName(x: Simple2): JmxObjectName =
      JmxObjectName("blended", Map("type" -> "simple", "name" -> x.name))
  }
}

final case class Simple2(
  name: String,
  aNumber: Long
)
