package blended.zio.jmx.metrics

sealed trait ServiceInvocationEvent {
  val id: String
}

final case class ServiceInvocationStarted(
  override val id: String,
  timestamp: Long,
  component: String,
  subComponents: Map[String, String] = Map.empty
) extends ServiceInvocationEvent {
  def summarizeId: String = component + subComponents.mkString("-", ",", "")
}

case class ServiceInvocationCompleted(override val id: String) extends ServiceInvocationEvent
case class ServiceInvocationFailed(override val id: String, cause: Option[Throwable] = None)
  extends ServiceInvocationEvent
