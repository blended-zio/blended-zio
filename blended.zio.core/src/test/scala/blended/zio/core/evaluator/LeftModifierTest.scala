package blended.zio.core.evaluator

import zio.duration._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object LeftModifierTest extends DefaultRunnableSpec {

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("The left modifier should")(
    testM("Simply take the n leftmost characters of a String")(checkM(for {
      s <- Gen.stringBounded(1, 100)(Gen.anyChar)
      n <- Gen.int(1, s.length)
    } yield (s, n)) { case (value, n) =>
      for {
        s <- LeftModifier.modifier(value, n.toString)
      } yield assert(s)(equalTo(value.take(n)))
    }),
    testM("Fail if the parameter is a negative number")(checkM(for {
      s <- Gen.stringBounded(1, 100)(Gen.anyChar)
      n <- Gen.int(Int.MinValue, -1)
    } yield (s, n)) { case (value, n) =>
      for {
        r <- LeftModifier.modifier(value, n.toString).run
      } yield assert(r)(fails(assertion("expect")() {
        case me: ModifierException =>
          me.segment.equals(value) && me.param.equals(n.toString) && me.msg.equals("Expected a number > 0")
        case _                     => false
      }))
    }),
    testM("Fail if the parameter is not a number")(checkM(for {
      s <- Gen.stringBounded(1, 100)(Gen.anyChar)
      n <- Gen.stringBounded(1, 10)(Gen.char('a', 'z'))
    } yield (s, n)) { case (value, n) =>
      for {
        r <- LeftModifier.modifier(value, n).run
      } yield assert(r)(fails(assertion("expect")() {
        case me: ModifierException => me.segment.equals(value) && me.param.equals(n)
        case _                     => false
      }))
    }),
    testM("yield the complete segment if the parameter is greater than the segment length")(checkM(for {
      s <- Gen.stringBounded(1, 100)(Gen.anyChar)
      n <- Gen.int(s.length, Int.MaxValue)
    } yield (s, n)) { case (value, n) =>
      for {
        s <- LeftModifier.modifier(value, n.toString)
      } yield assert(s)(equalTo(value))
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
