package blended.zio.core.evaluator

import zio.duration._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object LowerModifierTest extends DefaultRunnableSpec {

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("The lower modifier should")(
    testM("Simply transform the given String to lower case")(checkM(for {
      s <- Gen.stringBounded(1, 100)(Gen.anyChar)
    } yield s) { value =>
      for {
        s <- LowerModifier.modifier(value, "")
      } yield assert(s)(equalTo(value.toLowerCase))
    }),
    testM("fail if called with a null value")(for {
      r <- LowerModifier.modifier(null, "").run
    } yield assert(r)(fails(assertion("expect")() {
      case me: ModifierException =>
        me.segment.equals("") && me.param.equals("") && me.msg.equals("Segment can't be null")
      case _                     => false
    })))
  ) @@ timed @@ timeoutWarning(1.minute) @@ parallel
}
