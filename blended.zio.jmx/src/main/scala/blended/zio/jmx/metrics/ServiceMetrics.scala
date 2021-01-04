package blended.zio.jmx.metrics

import zio._
import zio.stm._

object ServiceMetrics {

  type ServiceMetrics = Has[Service]

  sealed trait ServiceMetricsException

  /**
   * Used when the referenced service summary can't be found
   * @param id The id of the requested service summary.
   */
  class ServiceSummaryNotFoundException(val id: String)
    extends RuntimeException(s"The service summary [$id] does not exist.")
    with ServiceMetricsException {

    override def equals(obj: Any): Boolean = obj match {
      case o: ServiceSummaryNotFoundException => o.id.equals(id)
      case _                                  => false
    }

    override def hashCode(): Int = id.hashCode
  }

  /**
   * Used when a [[ServiceInvocationStarted]] event shall be recorded for a
   * service invocation with an id that already exists.
   */
  class ServiceAlreadyStartedException(val s: ServiceInvocationStarted)
    extends RuntimeException(s"The start of service invocation [${s.id}] has already been recorded.")
    with ServiceMetricsException {

    override def equals(obj: Any): Boolean = obj match {
      case o: ServiceAlreadyStartedException => o.s.equals(s)
      case _                                 => false
    }

    override def hashCode(): Int = s.toString.hashCode
  }

  /**
   * Used when a [[ServiceInvocationFailed]] or [[ServiceInvocationCompleted]] event shall be processed,
   * but the referenced id is not currently active.
   * @param id The id of the requested service invocation
   */
  class ServiceInvocationNotFoundException(val id: String)
    extends RuntimeException(s"The service invocation [$id] is not active.")
    with ServiceMetricsException {

    override def equals(obj: Any): Boolean = obj match {
      case o: ServiceInvocationNotFoundException => o.id.equals(id)
      case _                                     => false
    }

    override def hashCode(): Int = id.hashCode
  }

  // doctag<service>
  trait Service {

    /**
     * Start to track a service invocation for a given start event.
     * If a tracker for that id is still active, a [[ServiceMetricsException]] will be returned.
     * Otherwise, an updated ServiceTrackingEntry will be returned.
     * @param start The start event containing the details of the service invocation to be tracked
     */
    def start(start: ServiceInvocationStarted): ZIO[Any, ServiceMetricsException, ServiceTrackingEntry]

    /**
     * Signal the successful completion of a service invocation. The invocation is referenced by
     * the id of a corresponding call to [[start]]. If the invocation cannot be found, a
     * [[ServiceMetricsException]] will be returned
     * @param s The completion event for the service invocation
     */
    def complete(s: ServiceInvocationCompleted): ZIO[Any, ServiceMetricsException, ServiceTrackingEntry]

    /**
     * Signal the successful completion of a service invocation. The invocation is referenced by
     * the id of a corresponding call to [[start]]. If the invocation cannot be found, a
     * [[ServiceMetricsException]] will be returned.
     * @param f The completion event for the service invocation
     */
    def failed(f: ServiceInvocationFailed): ZIO[Any, ServiceMetricsException, ServiceTrackingEntry]

    /**
     * Get the list of service invocation ids which are currently active.
     */
    def active: ZIO[Any, Nothing, List[String]]

    /**
     * Get the current Map of summaries recorded by the Service tracker
     */
    def summaries: ZIO[Any, Nothing, Map[String, ServiceTrackingEntry]]
  }
  // end:doctag<service>

  object Service {

    private val svcRef: Ref[Option[Service]] =
      Runtime.default.unsafeRun(Ref.make[Option[Service]](None))

    val live: ZIO[Any, Nothing, Service] = for {
      ref <- svcRef.get
      svc <- ZIO.fromOption(ref).orElse(createService)
    } yield svc

    private def createService: ZIO[Any, Nothing, Service] = for {
      inst <- ConcurrentInvocationTracker.make()
      svc   = new Service {
                override def start(s: ServiceInvocationStarted): ZIO[Any, ServiceMetricsException, ServiceTrackingEntry] =
                  inst.start(s)
                override def complete(
                  s: ServiceInvocationCompleted
                ): ZIO[Any, ServiceMetricsException, ServiceTrackingEntry]                                               = inst.complete(s)
                override def failed(f: ServiceInvocationFailed): ZIO[Any, ServiceMetricsException, ServiceTrackingEntry] =
                  inst.failed(f)
                override def active: ZIO[Any, Nothing, List[String]]                                                     = inst.active
                override def summaries: ZIO[Any, Nothing, Map[String, ServiceTrackingEntry]]                             = inst.summaries
              }
      _    <- svcRef.set(Some(svc))
    } yield svc
  }

  val live: Layer[Nothing, ServiceMetrics] = ZLayer.fromEffect(Service.live)
}

// doctag<tracker>
final class ConcurrentInvocationTracker private (
  val running: TMap[String, ServiceInvocationStarted],
  val summary: TMap[String, ServiceTrackingEntry]
) { self =>
// end:doctag<tracker>

  import ServiceMetrics._

  def active: ZIO[Any, Nothing, List[String]]                         = self.running.keys.commit
  def summaries: ZIO[Any, Nothing, Map[String, ServiceTrackingEntry]] = self.summary.toMap.commit

  // doctag<start>
  def start(evt: ServiceInvocationStarted): ZIO[Any, ServiceMetricsException, ServiceTrackingEntry] = (for {
    // Using the flip will turn an existing invocation into the error type, so that we can handle that
    // at the end with mapError
    _     <- getExistingActive(evt.id).flip
    _     <- self.running.put(evt.id, evt)
    // make sure we do have a summary entry in our summary map
    entry <- getOrCreateSummary(evt)
    // Record the service start
    upd    = updStarted(entry)
    _     <- self.summary.put(evt.summarizeId, upd)
  } yield (upd)).commit.mapError(_ => new ServiceAlreadyStartedException(evt))
  // end:doctag<start>

  def complete(evt: ServiceInvocationCompleted): ZIO[Any, ServiceMetricsException, ServiceTrackingEntry] =
    update(evt.id, success = true)

  def failed(evt: ServiceInvocationFailed): ZIO[Any, ServiceMetricsException, ServiceTrackingEntry] =
    update(evt.id, success = false)

  // doctag<update>
  private def update(id: String, success: Boolean): ZIO[Any, ServiceMetricsException, ServiceTrackingEntry] = (for {
    act   <- getExistingActive(id)
    sumId  = act.summarizeId
    entry <- getExistingSummary(sumId)
    upd    = if (success) updCompleted(act.timestamp)(entry) else updFailed(act.timestamp)(entry)
    _     <- self.summary.put(sumId, upd)
    _     <- self.running.delete(id)
  } yield (upd)).commit
  // end:doctag<update>

  // doctag<helpers>
  private val updStarted: ServiceTrackingEntry => ServiceTrackingEntry =
    e => e.copy(inflight = e.inflight + 1)

  private val updFailed: Long => ServiceTrackingEntry => ServiceTrackingEntry = started =>
    orig =>
      orig.copy(
        lastFailed = Some(System.currentTimeMillis()),
        failed = orig.failed.record(System.currentTimeMillis() - started),
        inflight = orig.inflight - 1
      )

  private val updCompleted: Long => ServiceTrackingEntry => ServiceTrackingEntry = started =>
    orig =>
      orig.copy(
        success = orig.success.record(System.currentTimeMillis() - started),
        inflight = orig.inflight - 1
      )

  private def getExistingSummary(sumId: String): STM[ServiceMetricsException, ServiceTrackingEntry] =
    STM.require(new ServiceSummaryNotFoundException(sumId))(self.summary.get(sumId))

  private def getExistingActive(id: String): STM[ServiceMetricsException, ServiceInvocationStarted] =
    STM.require(new ServiceInvocationNotFoundException(id))(self.running.get(id))

  private def addSummary(evt: ServiceInvocationStarted): STM[Nothing, ServiceTrackingEntry] = for {
    entry <- STM.succeed(ServiceTrackingEntry(evt.summarizeId))
    _     <- self.summary.put(evt.summarizeId, entry)
  } yield (entry)

  private def getOrCreateSummary(evt: ServiceInvocationStarted): STM[Nothing, ServiceTrackingEntry] =
    self.summary.get(evt.summarizeId).flatMap {
      case Some(e) => STM.succeed(e)
      case None    => addSummary(evt)
    }
  // end:doctag<helpers>
}

object ConcurrentInvocationTracker {
  def make(): ZIO[Any, Nothing, ConcurrentInvocationTracker] = (for {
    a <- TMap.empty[String, ServiceInvocationStarted]
    t <- TMap.empty[String, ServiceTrackingEntry]
  } yield (new ConcurrentInvocationTracker(a, t))).commit
}
