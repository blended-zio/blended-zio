package blended.zio.itest

import zio._
import zio.clock.Clock
import zio.duration._
import zio.logging._
import zio.test.DefaultRunnableSpec
import zio.test.TestAspect._
import zio.test._

import blended.zio.streams._

object KeepAliveMonitorTest extends DefaultRunnableSpec {

  override def spec = (suite("The KeepAliveMonitor should")(
    signalKeepAlive,
    keepAlive
  ).provideCustomLayer(JmsTestEnv.withoutBroker)) @@ timed @@ timeout(3.seconds) @@ parallel

  private val signalKeepAlive = testM("signal when the maximum keep alive is reached")(for {
    logger <- ZIO.service[Logger[String]]
    clk    <- ZIO.environment[Clock]
    kam    <- DefaultKeepAliveMonitor.make("signal", 3, clk, logger)
    _      <- kam.run(10.millis)
    cnt    <- kam.current
  } yield assertTrue(cnt == 3))

  private val keepAlive = testM("do not signal when regular alive events are triggered")(for {
    logger <- ZIO.service[Logger[String]]
    clk    <- ZIO.environment[Clock]
    kam    <- DefaultKeepAliveMonitor.make("alive", 3, clk, logger)
    f      <- kam.run(50.millis).fork
    _      <- (kam.alive <* log.trace(s"Booh")).schedule(Schedule.spaced(20.millis)).fork
    _      <- f.interrupt.schedule(Schedule.duration(500.millis)).fork
    cnt    <- f.join *> kam.current
  } yield assertTrue(cnt < 3))

}
