package blended.zio.streams.jms

import java.text.SimpleDateFormat

import javax.jms._
import java.util.concurrent.atomic.AtomicLong

import zio.duration.Duration

sealed trait JmsApiObject {
  def id: String
  def timestamp: String         = JmsApiObject.sdf.format(System.currentTimeMillis())
  override def toString: String = s"${getClass().getSimpleName()}($id)"
}

object JmsApiObject {
  private val sdf: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss:SSS")
}

// doctag<connection>
final case class JmsConnectionFactory(
  override val id: String,
  factory: ConnectionFactory,
  reconnectInterval: Duration
) extends JmsApiObject {
  def connId(clientId: String): String = s"$id-$clientId"
}
// end:doctag<connection>

final case class JmsConnection(
  factory: JmsConnectionFactory,
  conn: Connection,
  clientId: String
) extends JmsApiObject {

  private val sessionCnt: AtomicLong = new AtomicLong(0L)
  def nextSessionName: String        = s"S-$timestamp-${sessionCnt.incrementAndGet()}"
  override def id: String            = factory.connId(clientId)
}

final case class JmsSession(
  conn: JmsConnection,
  name: String,
  session: Session
) extends JmsApiObject {

  private val prodCnt: AtomicLong = new AtomicLong(0L)
  private val consCnt: AtomicLong = new AtomicLong(0L)

  def nextProdName: String = s"P-${prodCnt.incrementAndGet()}"
  def nextConsName: String = s"C-${consCnt.incrementAndGet()}"

  override def id: String = s"${conn.id}-$name"
}

final case class JmsProducer(
  session: JmsSession,
  name: String,
  producer: MessageProducer
) extends JmsApiObject {
  override def id: String = s"${session.id}-$name"
}

final case class JmsConsumer(
  session: JmsSession,
  name: String,
  dest: JmsDestination,
  consumer: MessageConsumer
) extends JmsApiObject {
  override def id: String = s"${session.id}--$name-${dest.asString}"
}
