package blended.zio.jmx.metrics

/**
 * A simple helper class to track the count und total sum of individual recordings.
 * @param count - The current count
 * @param totalMsec - The current total
 * @param minMsec - The current minimum
 * @param maxMsec - The current maximum
 */
final case class Accumulator(
  count: Long = 0L,
  totalMsec: Long = 0L,
  minMsec: Option[Long] = None,
  maxMsec: Option[Long] = None
) {

  def record(msec: Long): Accumulator = copy(
    count = count + 1,
    totalMsec = totalMsec + msec,
    minMsec = minMsec.map(Math.min(_, msec)).orElse(Some(msec)),
    maxMsec = maxMsec.map(Math.max(_, msec)).orElse(Some(msec))
  )

  def avg: Double =
    if (count == 0) 0d else totalMsec.toDouble / count

}
