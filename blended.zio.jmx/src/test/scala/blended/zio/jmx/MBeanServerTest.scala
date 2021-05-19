package blended.zio.jmx

import zio._
import zio.duration._
import zio.logging.slf4j._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object MBeanServerTest extends DefaultRunnableSpec {

  override def spec = suite("The JvmMBeanServer should")(
    allMBeanNames,
    existingMBeanName,
    specificMBeanName,
    someMBeanNames
  ).provideCustomLayerShared(mbeanLayer) @@ timed @@ timeoutWarning(1.minute) @@ parallel

  // doctag<zlayer>
  private val logSlf4j = Slf4jLogger.make((_, message) => message)

  private val mbeanLayer: ZLayer[Any, Nothing, MBeanServerFacade.MBeanServerFacade] =
    logSlf4j >>> MBeanServerFacade.live
  // end:doctag<zlayer>

  private val allMBeanNames = testM("allow to retrieve all mbean names in a plain JVM")(
    for {
      svr   <- ZIO.service[MBeanServerFacade.Service]
      names <- svr.allMbeanNames()
    } yield (
      assert(names)(isNonEmpty)
    )
  )

  private val specificMBeanName = testM("allow to retrieve a specific mbean name")(
    for {
      svr   <- ZIO.service[MBeanServerFacade.Service]
      on    <- JmxObjectName.make("java.lang:type=Memory")
      names <- svr.mbeanNames(Some(on))
    } yield (
      assert(names)(hasSize(equalTo(1))) &&
        assert(names.head)(equalTo(on))
    )
  )

  private val someMBeanNames = testM("Allow to query for a group of names")(
    for {
      svr   <- ZIO.service[MBeanServerFacade.Service]
      on    <- JmxObjectName.make("java.lang:type=MemoryPool")
      names <- svr.mbeanNames(Some(on))
      anc   <- ZIO.collect(names)(n => on.isAncestor(n))
    } yield assert(names)(hasSize(isGreaterThan(1))) &&
      assert(anc)(forall(isTrue))
  )

  private val existingMBeanName = testM("map an existing mbean to a MBean Info object")(
    for {
      svr   <- ZIO.service[MBeanServerFacade.Service]
      names <- svr.allMbeanNames()
      infos <- ZIO.foreach(names)(n => svr.mbeanInfo(n))
    } yield (
      assert(infos.size)(equalTo(names.size))
    )
  )
}
