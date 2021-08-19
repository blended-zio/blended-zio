package blended.zio.streams

import zio._
import zio.clock._
import zio.duration._
import zio.logging.Logger

class KeepAliveException(val id: String, val count: Int)
  extends Exception(
    s"KeepAliveMonitor [$id] has reached maximum number of missed keep alives : [$count]"
  )

/**
 * A keep alive monitor that that expects a regular keep alive signal. Every time the
 * signal is missed, a counter is increased up to a given maximum.
 *
 * The <code>run</code> effect executes until the given maximum is reached and then terminates
 * with the current count of missed keep alives.
 *
 * The instance of the monitor will die once it has been triggered and a new instance
 * should be created by users of the monitor once they have taken whatever measures
 * they require to recover from the missed keep alives.
 */

/* TODO:
 * - It is probably better to pass in the effect that shall be executed as a parameter to
 *   the run method. That would relieve users of the monitor of some boilerplate code
 *   required for house keeping.
 */

// doctag<trait>
trait KeepAliveMonitor {

  /**
   * An id to identify the monitor in the logs and metrics.
   */
  val id: String

  /**
   * The maximum number of allowed missed keep alive signals.
   */
  val allowed: Int

  /**
   * Signal that the monitored entity is still alive. This will cause to reset the missed
   * counter to 0.
   */
  def alive: ZIO[Any, Nothing, Unit]

  /**
   * Start the monitor with a given interval. At every interval tick the counter
   * for missed keep alives will be incremented. If the counter reaches the maximum allowed
   * missed keep alives, run will terminate and yield the current counter (which happens to
   * be the allowed maximum).
   */
  def run(interval: Duration): ZIO[Any, Nothing, Int]

  /**
   * Return the current count of missed keep alives
   */
  def current: ZIO[Any, Nothing, Int]
}
// end:doctag<trait>

object DefaultKeepAliveMonitor {

  def make(
    name: String,
    allowedKeepAlives: Int,
    logger: Logger[String]
  ) = for {
    init <- Ref.make[Int](0)
    impl  = new DefaultKeepAliveMonitor(name, allowedKeepAlives, logger) {
              override private[streams] val missed: Ref[Int] = init
            }
    kam   = new KeepAliveMonitor {
              override val id: String                                      = name
              override val allowed: Int                                    = allowedKeepAlives
              override def alive: ZIO[Any, Nothing, Unit]                  = impl.alive
              override def run(interval: Duration): ZIO[Any, Nothing, Int] = impl.run(interval)
              override def current: ZIO[Any, Nothing, Int]                 = impl.current
            }
  } yield kam
}

sealed abstract private class DefaultKeepAliveMonitor private (
  name: String,
  allowedKeepAlives: Int,
  logger: Logger[String]
) {

  private[streams] val missed: Ref[Int]

  private def alive = for {
    _ <- logger.trace(s"Reset keep alive monitor [$name]")
    _ <- missed.set(0)
  } yield ()

  private[streams] def current = missed.get

  // doctag<run>
  private[streams] def run(interval: Duration) = {

    def go: ZIO[Any, Nothing, Unit] = ZIO.ifM(missed.updateAndGet(_ + 1).map(_ == allowedKeepAlives))(
      ZIO.unit,
      (go.schedule(Schedule.duration(interval)).flatMap(_ => ZIO.unit)).provideLayer(Clock.live)
    )

    (for {
      _ <- logger.trace(s"Starting KeepAliveMonitor [$name]")
      _ <- go.schedule(Schedule.duration(interval))
      c <- missed.get
      _ <- logger.trace(s"KeepAliveMonitor [$name] finished with maximum keep alives of [$c]")
    } yield (c)).provideLayer(Clock.live)
  }
  // end:doctag<run>
}
