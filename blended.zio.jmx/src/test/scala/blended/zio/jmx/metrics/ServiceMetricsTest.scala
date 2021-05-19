package blended.zio.jmx.metrics

import java.util.concurrent.atomic.AtomicInteger

import zio._
import zio.duration._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

import blended.zio.jmx.metrics.ServiceMetrics.{ ServiceAlreadyStartedException, ServiceInvocationNotFoundException }

object ServiceMetricsTest extends DefaultRunnableSpec {

  override def spec = suite("The ConcurrentServiceMetrics should")(
    recordStarted,
    recordComplete,
    recordFailed,
    failOnDuplicate,
    failOnUnstarted
  ).provideCustomLayer(ServiceMetrics.live) @@ timed @@ timeoutWarning(1.minute)

  private val recordStarted = testM("record a service started invocation correctly") {
    for {
      tracker <- ZIO.service[ServiceMetrics.Service]
      s       <- start
      _       <- tracker.start(s)
      running <- tracker.active
      sum     <- tracker.summaries
    } yield (
      assert(running)(contains(s.id)) &&
        assert(sum.get(s.summarizeId).map(_.inflight).get)(isGreaterThan(0L))
    )
  }

  // doctag<complete>
  private val recordComplete = testM("record a service completion correctly") {
    for {
      tracker <- ZIO.service[ServiceMetrics.Service]
      s       <- start
      c       <- completed(s)
      old     <- tracker.summaries
      e       <- ZIO.effectTotal(old.getOrElse(s.summarizeId, ServiceTrackingEntry(s.summarizeId)))
      _       <- tracker.start(s)
      _       <- tracker.complete(c)
      sum     <- tracker.summaries
    } yield assert(sum(s.summarizeId).success.count)(isGreaterThanEqualTo(e.success.count))
  }
  // end:doctag<complete>

  private val recordFailed = testM("record a service failure correctly") {
    for {
      tracker <- ZIO.service[ServiceMetrics.Service]
      s       <- start
      f       <- error(s)
      old     <- tracker.summaries
      e       <- ZIO.effectTotal(old.getOrElse(s.summarizeId, ServiceTrackingEntry(s.summarizeId)))
      _       <- tracker.start(s)
      _       <- tracker.failed(f)
      sum     <- tracker.summaries
    } yield assert(sum(s.summarizeId).failed.count)(isGreaterThanEqualTo(e.failed.count))
  }

  private val failOnDuplicate = testM("Fail if a service invocation is recorded twice") {
    for {
      tracker <- ZIO.service[ServiceMetrics.Service]
      s       <- start
      _       <- tracker.start(s)
      s2      <- tracker.start(s).run
    } yield (assert(s2)(fails(equalTo(new ServiceAlreadyStartedException(s)))))
  }

  private val failOnUnstarted = testM("Fail if a service completion with an unkown id") {
    for {
      tracker <- ZIO.service[ServiceMetrics.Service]
      c       <- ZIO.effectTotal(ServiceInvocationCompleted(counter.incrementAndGet().toString))
      r       <- tracker.complete(c).run
    } yield (assert(r)(fails(equalTo(new ServiceInvocationNotFoundException(c.id)))))
  }

  /* --- Helper methods --- */

  private def start: ZIO[Any, Nothing, ServiceInvocationStarted] =
    ZIO.effectTotal(
      ServiceInvocationStarted(counter.incrementAndGet().toString, System.currentTimeMillis(), "myComponent")
    )

  private def completed(start: ServiceInvocationStarted): ZIO[Any, Nothing, ServiceInvocationCompleted] =
    ZIO.effectTotal(
      ServiceInvocationCompleted(start.id)
    )

  private def error(start: ServiceInvocationStarted): ZIO[Any, Nothing, ServiceInvocationFailed] = ZIO.effectTotal(
    ServiceInvocationFailed(start.id, None)
  )

  private val counter: AtomicInteger = new AtomicInteger(0)
}
