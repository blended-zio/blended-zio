package blended.zio.itest

import scala.reflect.ClassTag

import zio.test._
import blended.zio.streams._

object EnvelopeAssertion {

  def hasHeader[T: ClassTag](name: String, value: MsgProperty[T]): Assertion[FlowEnvelope[_, _]] =
    Assertion.assertion("hasHeader")(AssertionM.Render.param(name), AssertionM.Render.param(value)) { env =>
      env.header.get(name) == Right(value.value)
    }

  def hasContent[T](expected: T): Assertion[FlowEnvelope[_, T]] =
    Assertion.assertion("hasContent")(AssertionM.Render.param(expected)) { env =>
      env.content == expected
    }

}
