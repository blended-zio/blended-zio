package blended.zio.itest

import scala.reflect.ClassTag

import zio.test._
import blended.zio.streams._

object EnvelopeAssertion {

  def hasHeader[T: ClassTag](name: String, value: MsgProperty[T]): Assertion[FlowEnvelope[_, _]] =
    Assertion.assertion("hasHeader")(AssertionM.Render.param(name), AssertionM.Render.param(value))(
      _.header.get[T](name).isRight
    )

}
