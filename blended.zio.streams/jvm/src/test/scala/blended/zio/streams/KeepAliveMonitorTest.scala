package blended.zio.streams

import zio.test.DefaultRunnableSpec

import zio._
import zio.duration._

import zio.logging._
import zio.logging.slf4j._

import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect._

object KeepAliveMonitorTest extends DefaultRunnableSpec {

  private val logEnv: ZLayer[Any, Nothing, ZEnv with Logging] =
    ZEnv.live ++ Slf4jLogger.make((_, message) => message)

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = (suite("The KeepAliveMonitor should")(
    signalKeepAlive,
    keepAlive
  ).provideCustomLayer(logEnv)) @@ timed @@ timeout(3.seconds) @@ parallel

  private val signalKeepAlive = testM("signal when the maximum keep alive is reached")(for {
    kam <- DefaultKeepAliveMonitor.make("signal", 3)
    f   <- kam.run(10.millis).fork
    _   <- f.join
    cnt <- kam.current
  } yield assert(cnt)(equalTo(3)))

  private val keepAlive = testM("do not signal when regular alive events are triggered")(for {
    kam <- DefaultKeepAliveMonitor.make("alive", 3)
    _   <- kam.alive.schedule(Schedule.spaced(20.millis)).fork
    f   <- kam.run(50.millis).fork
    f2  <- f.interrupt.schedule(Schedule.duration(500.millis)).fork
    _   <- f2.join
    cnt <- kam.current
  } yield assert(cnt)(isLessThan(3)))

}
