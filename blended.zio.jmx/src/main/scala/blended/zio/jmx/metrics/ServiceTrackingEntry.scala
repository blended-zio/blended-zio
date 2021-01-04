package blended.zio.jmx.metrics

final case class ServiceTrackingEntry(
  id: String,
  success: Accumulator = Accumulator(),
  failed: Accumulator = Accumulator(),
  lastFailed: Option[Long] = None,
  inflight: Long = 0L
)
