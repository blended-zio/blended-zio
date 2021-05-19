package blended.zio.jolokia

import zio._
import zio.duration._
import zio.logging._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

import JolokiaClient._

object JolokiaClientTest extends DefaultRunnableSpec {

  private val logLayer = Logging.console(
    logLevel = LogLevel.Info,
    format = LogFormat.ColoredLogFormat()
  ) >>> Logging.withRootLoggerName(getClass().getSimpleName())

  override def spec = (suite("The Jolokia client should")(
    connect,
    searchByDomain,
    searchComplete,
    read,
    exec
  ) @@ timed @@ timeoutWarning(1.minute)).provideCustomLayer(logLayer)

  private val connect = testM("Connect to Jolokia")(for {
    address <- good
    version <- JolokiaClient.create(address).use { client =>
                 client.version
               }
    _       <- log.debug(s"$version")
  } yield assert(version.agent)(equalTo("1.6.2")))

  private val searchByDomain = testM("Allow to search for MBeans by domain only")(for {
    address <- good
    mbeans  <- JolokiaClient.create(address).use { client =>
                 client.search(MBeanSearchDef("java.lang"))
               }
    _       <- log.debug(s"$mbeans")
  } yield assert(mbeans.mbeanNames.isEmpty)(isFalse))

  private val searchComplete = testM("Allow to search for MBeans by domain and properties")(for {
    address <- good
    mbeans  <- JolokiaClient.create(address).use { client =>
                 client.search(MBeanSearchDef("java.lang"))
               }
    _       <- log.debug(s"$mbeans")
  } yield assert(mbeans.mbeanNames.isEmpty)(isFalse))

  private val read = testM("Allow to read a specific MBean")(for {
    address <- good
    mBean   <- JolokiaClient.create(address).use { client =>
                 client.read("java.lang:type=Memory")
               }
    _       <- log.debug(s"$mBean")
  } yield assert(mBean.objectName)(equalTo("java.lang:type=Memory")))

  private val exec = testM("Allow to execute a given operation on a MBean")(
    for {
      address <- good
      res     <- JolokiaClient.create(address).use { client =>
                   client.exec(
                     OperationExecDef(
                       objectName = "java.lang:type=Threading",
                       operationName = "dumpAllThreads(boolean,boolean)",
                       parameters = List("true", "true")
                     )
                   )
                 }
      _       <- log.debug(s"$res")
    } yield assert(res.objectName)(equalTo("java.lang:type=Threading")) &&
      assert(res.operationName)(equalTo("dumpAllThreads(boolean,boolean)"))
  )

  private val good =
    ZIO.effectTotal(System.getProperty("jolokia.agent")).map(s => JolokiaAddress(s))
}
