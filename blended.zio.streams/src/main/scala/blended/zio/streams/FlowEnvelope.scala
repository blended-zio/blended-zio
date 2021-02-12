package blended.zio.streams

sealed trait MsgProperty[T] {
  def value: T
}

object MsgProperty {

  final case class IntMsgProperty(override val value: Int)       extends MsgProperty[Int]
  final case class StringMsgProperty(override val value: String) extends MsgProperty[String]

}

final case class FlowEnvelope[T](
  props: Map[String, MsgProperty[_]],
  content: T
)

object Compile {

  import MsgProperty._

  val env1 = FlowEnvelope[String](Map.empty, "")
  val env2 = FlowEnvelope[Unit](Map("foo" -> StringMsgProperty("bar")), ())
}
