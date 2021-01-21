package blended.zio.streams

import zio._
import zio.duration._
import zio.console._
import zio.stream.ZStream

object DummySource {
  def create(v: String, d: Duration, fail: Int): ZIO[ZEnv, Nothing, DummySource] = for {
    counter    <- Ref.make(0)
    recovering <- Ref.make(false)
  } yield new DummySource(v, counter, recovering, d, fail: Int)
}

class DummySource private (v: String, counter: Ref[Int], recovering: Ref[Boolean], d: Duration, fail: Int) {

  private val recover: ZIO[ZEnv, Nothing, Unit] = recovering.set(false) *> putStrLn("reset")

  private def nextValue: ZIO[ZEnv, Throwable, String] = for {
    c <- counter.updateAndGet(_ + 1)
    _ <-
      if (c % fail == 0) {
        ZIO.fail(new Exception("Boom"))
      } else {
        ZIO.unit
      }
  } yield s"$v : $c"

  def next: ZIO[ZEnv, Nothing, Option[String]] = ZIO
    .ifM(recovering.get)(
      ZIO.succeed(None),
      nextValue.map(Some(_))
    )
    .catchAll(_ => (recovering.set(true) *> recover.schedule(Schedule.duration(d))).flatMap(_ => ZIO.succeed(None)))
}

object DemoApp extends App {

  private def makeStream(
    effect: ZIO[ZEnv, Nothing, Option[String]],
    tag: String
  ): ZStream[ZEnv, Nothing, Option[String]] = ZStream.repeatEffect(putStr(tag) *> effect)

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] = for {
    src1 <- DummySource.create("T1", 1.second, 50)
    src2 <- DummySource.create("T2", 250.millis, 20)

    stop <- Promise.make[Nothing, Boolean]
    _    <- (getStrLn.flatMap(_ => stop.succeed(true))).fork

    _ <- makeStream(src1.next, "-")
           .merge(makeStream(src2.next, "+"))
           .interruptWhen(stop)
           .collect { case Some(s) => s }
           .foreach(s => putStrLn(s))

  } yield ExitCode.success
}
