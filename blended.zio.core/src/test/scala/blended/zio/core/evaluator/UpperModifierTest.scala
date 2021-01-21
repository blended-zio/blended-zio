package blended.zio.core.evaluator

import zio.duration._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object UpperModifierTest extends DefaultRunnableSpec {

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("The upper modifier should")(
    testM("Simply transform the given String to upper case")(checkM(for {
      s <- Gen.stringBounded(1, 100)(Gen.anyChar)
    } yield s) { value =>
      for {
        s <- UpperModifier.modifier(value, "")
      } yield assert(s)(equalTo(value.toUpperCase))
    }),
    testM("fail if called with a null value")(for {
      r <- UpperModifier.modifier(null, "").run
    } yield assert(r)(fails(assertion("expect")() {
      case me: ModifierException =>
        me.segment.equals("") && me.param.equals("") && me.msg.equals("Segment can't be null")
      case _                     => false
    })))
  ) @@ timed @@ timeoutWarning(1.minute) @@ parallel
}
