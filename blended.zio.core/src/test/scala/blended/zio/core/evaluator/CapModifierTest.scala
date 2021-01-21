package blended.zio.core.evaluator

import zio.duration._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object CapModifierTest extends DefaultRunnableSpec {

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("The capitalize modifier should")(
    testM("Simply capitalize the given String")(checkM(for {
      s <- Gen.stringBounded(1, 100)(Gen.anyChar)
    } yield s) { value =>
      for {
        s <- CapModifier.modifier(value, "")
      } yield assert(s)(equalTo(value.capitalize))
    }),
    testM("fail if called with a null value")(for {
      r <- CapModifier.modifier(null, "").run
    } yield assert(r)(fails(assertion("expect")() {
      case me: ModifierException =>
        me.segment.equals("") && me.param.equals("") && me.msg.equals("Segment can't be null")
      case _                     => false
    })))
  ) @@ timed @@ timeoutWarning(1.minute) @@ parallel
}
