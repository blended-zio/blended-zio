package blended.zio.streams.jms

import javax.jms._
import zio._

object JmsDestination {

  private[jms] val destSeparator = ":"
  private[jms] val TOPICTAG      = "topic"
  private[jms] val QUEUETAG      = "queue"

  def fromString(destName: String): ZIO[Any, IllegalArgumentException, JmsDestination] = for {
    parts <- ZIO.succeed(Option(destName).getOrElse("").split(destSeparator))
    d     <- parts.length match {
               case 0 => ZIO.fail(new IllegalArgumentException("No destination name given"))
               case 1 => ZIO.succeed(JmsQueue(parts(0)))
               case 2 =>
                 parts(0) match {
                   case QUEUETAG => ZIO.succeed(JmsQueue(parts(1)))
                   case TOPICTAG => ZIO.succeed(JmsTopic(parts(1)))
                   case _        =>
                     ZIO.fail(
                       new IllegalArgumentException(
                         s"String representation of JmsDestination must start with either [$QUEUETAG:] or [$TOPICTAG:]"
                       )
                     )
                 }
               case 3 =>
                 if (parts(0) == TOPICTAG) {
                   ZIO.succeed(JmsDurableTopic(parts(2), parts(1)))
                 } else {
                   throw new IllegalArgumentException("Only names for durable Topics have 3 components")
                 }

               case _ => throw new IllegalArgumentException(s"Illegal format for destination name [$destName]")
             }
  } yield (d)

  def fromDestination(jmsDest: Destination): ZIO[Any, IllegalArgumentException, JmsDestination] = jmsDest match {
    case t: javax.jms.Topic => ZIO.succeed(JmsTopic(t.getTopicName()))
    case q: javax.jms.Queue => ZIO.succeed(JmsQueue(q.getQueueName()))
    case _                  => ZIO.fail(new IllegalArgumentException(s"Unknown destination type [${jmsDest.getClass().getName()}]"))
  }
}

sealed trait JmsDestination {
  val name: String
  def create(session: JmsSession): ZIO[Any, JMSException, Destination]
  def asString: String
}

final case class JmsTopic(override val name: String) extends JmsDestination {

  override def create(jmsSess: JmsSession): ZIO[Any, JMSException, Destination] =
    ZIO.effect(jmsSess.session.createTopic(name)).refineOrDie { case t: JMSException => t }

  override val asString: String                                                 = s"${JmsDestination.TOPICTAG}${JmsDestination.destSeparator}$name"
}

final case class JmsDurableTopic(override val name: String, subscriberName: String) extends JmsDestination {

  override def create(jmsSess: JmsSession): ZIO[Any, JMSException, Destination] =
    ZIO.effect(jmsSess.session.createTopic(name)).refineOrDie { case t: JMSException => t }

  override val asString: String                                                 =
    s"${JmsDestination.TOPICTAG}${JmsDestination.destSeparator}${subscriberName}${JmsDestination.destSeparator}$name"
}

final case class JmsQueue(override val name: String) extends JmsDestination {

  override def create(jmsSess: JmsSession): ZIO[Any, JMSException, Destination] =
    ZIO.effect(jmsSess.session.createQueue(name)).refineOrDie { case t: JMSException => t }

  override val asString: String                                                 = name
}
