package blended.zio.core.evaluator

import zio._
import zio.duration._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object ReplaceModifierTest extends DefaultRunnableSpec {

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("The replace modifier should")(
    testM("Simply replace all occurrences of p1 with p2")(checkM(for {
      s  <- Gen.stringBounded(5, 100)(Gen.anyChar)
      n  <- Gen.int(0, s.length - 1)
      p2 <- Gen.stringN(2)(Gen.anyChar)
    } yield (s, n, p2)) { case (value, n, p2) =>
      for {
        p1 <- ZIO.succeed(value.substring(n, n + 1))
        s  <- ReplaceModifier.modifier(value, s"$p1:$p2")
      } yield assert(s)(equalTo(value.replaceAll(p1, p2)))
    }),
    testM("Fail if the parameter has more than 2 parts")(checkM(for {
      s  <- Gen.stringBounded(5, 100)(Gen.anyChar)
      n  <- Gen.int(0, s.length - 1)
      p2 <- Gen.stringN(2)(Gen.anyChar)
    } yield (s, n, p2)) { case (value, n, p2) =>
      for {
        p1    <- ZIO.succeed(value.substring(n, n + 1))
        param <- ZIO.succeed(s"$p1:$p2:$p2")
        r     <- ReplaceModifier.modifier(value, param).run
      } yield assert(r)(fails(assertion("expect")() {
        case me: ModifierException =>
          me.segment.equals(value) && me.param.equals(param) && me.msg.equals(
            "Parameter should have the form '<from>:<to>'"
          )
        case _                     => false
      }))
    }),
    testM("Fail if the parameter has only 1 part")(checkM(for {
      s <- Gen.stringBounded(5, 100)(Gen.anyChar)
      n <- Gen.int(0, s.length - 1)
    } yield (s, n)) { case (value, n) =>
      for {
        p1 <- ZIO.succeed(value.substring(n, n + 1))
        r  <- ReplaceModifier.modifier(value, p1).run
      } yield assert(r)(fails(assertion("expect")() {
        case me: ModifierException =>
          me.segment.equals(value) && me.param.equals(p1) && me.msg.equals(
            "Parameter should have the form '<from>:<to>'"
          )
        case _                     => false
      }))
    }),
    testM("fail if called with a null value")(checkM(for {
      n <- Gen.int(1, Int.MaxValue)
    } yield n) { n =>
      for {
        r <- LeftModifier.modifier(null, n.toString).run
      } yield assert(r)(fails(assertion("expect")() {
        case me: ModifierException =>
          me.segment.equals("") && me.param.equals(n.toString) && me.msg.equals("Segment can't be null")
        case _                     => false
      }))
    })
  ) @@ timed @@ timeoutWarning(1.minute) @@ parallel
}
