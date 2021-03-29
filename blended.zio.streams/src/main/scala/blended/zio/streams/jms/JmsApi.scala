package blended.zio.streams.jms

import javax.jms._

import zio._
import zio.logging._
import zio.blocking._
import zio.stream._
import zio.duration._

import JmsConnectionManager._
import JmsDestination._
import JmsApiObject._

import blended.zio.core.RuntimeId
import RuntimeId.RuntimeIdService
import blended.zio.streams.FlowEnvelope

object JmsApi {

  type JmsEnv        = ZEnv with Logging with JmsConnectionManagerService with RuntimeIdService
  type JmsEncoder[T] = JmsProducer => FlowEnvelope[_, T] => ZIO[Blocking, JMSException, Message]

  def defaultJmsEnv[R, E](
    logging: ZLayer[R, E, Logging]
  ): ZLayer[R, E, Logging with JmsConnectionManagerService with RuntimeIdService] =
    logging ++ JmsConnectionManager.default ++ RuntimeId.default

  val stringEnvelopeEncoder: JmsEncoder[String] = p =>
    s =>
      effectBlocking {
        p.session.session.createTextMessage(s.content)
      }.mapError(mapException)

  def connect(
    cf: JmsConnectionFactory,
    clientId: String
  ) = for {
    mgr <- ZIO.service[JmsConnectionManager.Service]
    con <- mgr.connect(cf, clientId)
  } yield con

  def reconnect(
    con: JmsConnection,
    cause: Option[Throwable]
  ) = for {
    mgr <- ZIO.service[JmsConnectionManager.Service]
    _   <- mgr.reconnect(con, cause)
  } yield ()

  def createSession(con: JmsConnection) = ZManaged.make(
    (for {
      rid <- ZIO.service[RuntimeId.Service]
      js  <- effectBlocking(
               con.conn.createSession(false, Session.AUTO_ACKNOWLEDGE)
             )
      id  <- rid.nextId(con.id)
      s    = JmsSession(con, s"S-$id", js)
      _   <- log.debug(s"Created JMS Session [$s]")
    } yield s).mapError(mapException)
  )(s =>
    for {
      _ <- log.debug(s"Closing JMS Session [$s")
      _ <- effectBlocking(s.session.close()).catchAll(t => logException(s"Error closing JMS session [$s]", t))
    } yield ()
  )

  def createProducer(js: JmsSession) = ZManaged.make(for {
    rid <- ZIO.service[RuntimeId.Service]
    id  <- rid.nextId(js.id)
    p   <- effectBlocking(
             js.session.createProducer(null)
           ).map(prod => JmsProducer(js, s"P-$id", prod)).mapError(mapException)
    _   <- log.debug(s"Created JMS Producer [$p]")
  } yield p)(p =>
    for {
      _ <- log.debug(s"Closing JMS Producer [$p]")
      _ <- effectBlocking(p.producer.close()).catchAll(t => logException(s"Error closing JMS Producer [$p]", t))
    } yield ()
  )

  def createConsumer(js: JmsSession, dest: JmsDestination) =
    ZManaged.make(
      for {
        rid <- ZIO.service[RuntimeId.Service]
        id  <- rid.nextId(js.id)
        d   <- dest.create(js)
        c   <- effectBlocking {
                 dest match {
                   case JmsDurableTopic(_, subscriberName) =>
                     js.session.createDurableSubscriber(d.asInstanceOf[Topic], subscriberName)
                   case _                                  => js.session.createConsumer(d)
                 }
               }.map(cons => JmsConsumer(js, s"C-$id", dest, cons)).mapError(mapException)
        _   <- log.debug(s"Created JMS Consumer [$c]")
      } yield c
    )(c =>
      for {
        _ <- log.debug(s"Closing Consumer [$c]")
        _ <- effectBlocking(c.consumer.close()).catchAll(t => logException(s"Error closing JMS consumer [${c.id}]", t))
      } yield ()
    )

  // doctag<send>
  def send[T](
    content: FlowEnvelope[_, T],
    prod: JmsProducer,
    dest: JmsDestination,
    encode: JmsEncoder[T]
  ) = (for {
    msg <- encode(prod)(content)
    d   <- dest.create(prod.session)
    _   <- effectBlocking(prod.producer.send(d, msg))
    _   <- log.debug(s"Message [$content] sent successfully with [$prod] to [${dest.asString}]")
  } yield ()).flatMapError { t =>
    log.warn(s"Error sending message with [$prod] to [$dest]: [${t.getMessage()}]") *> ZIO.succeed(t)
  }.mapError(mapException)
  // end:doctag<send>

  // doctag<receive>
  def receive(cons: JmsConsumer) = (for {
    msg <- effectBlocking(Option(cons.consumer.receiveNoWait()))
    _   <- ZIO.foreach(msg)(msg => log.debug(s"Received [$msg] with [$cons]"))
  } yield msg).flatMapError { t =>
    log.warn(s"Error receiving message with [$cons] : [${t.getMessage()}]") *> ZIO.succeed(t)
  }.mapError(mapException)
  // end:doctag<receive>

  // doctag<stream>
  def jmsStream(cons: JmsConsumer) =
    ZStream.repeatEffect(FlowEnvelope.fromEffect(receive(cons))).collect { case FlowEnvelope(id, m, Some(c)) =>
      FlowEnvelope(id, m, c)
    }
  // end:doctag<stream>

  def recoveringJmsStream(
    cf: JmsConnectionFactory,
    clientId: String,
    dest: JmsDestination,
    retryInterval: Duration
  ) = for {
    streamFact <- RecoveringJmsStream.make(cf, clientId, retryInterval)
    str        <- streamFact.stream(dest)
  } yield str

  // doctag<sink>
  def jmsSink[T](
    prod: JmsProducer,
    dest: JmsDestination,
    encode: JmsEncoder[T]
  ) =
    ZSink.foreach[JmsEnv, JMSException, FlowEnvelope[_, T]](c => send[T](c, prod, dest, encode))
  // end:doctag<sink>

  def recoveringJmsSink[T](
    cf: JmsConnectionFactory,
    clientId: String,
    dest: JmsDestination,
    retryInterval: Duration,
    encode: JmsEncoder[T]
  ) = for {
    sinkFact <- RecoveringJmsSink.make[T](cf, clientId, encode)
    s        <- sinkFact.sink(dest, retryInterval)
  } yield s

  private def logException(msg: => String, t: Throwable) = log.warn(s"$msg : ${t.getMessage()}")

  private val mapException: Throwable => JMSException = t =>
    t match {
      case t: JMSException => t
      case e: Exception    =>
        val je = new JMSException(e.getMessage())
        je.setLinkedException(e)
        je
      case o               => new JMSException(o.getMessage())
    }

  // private def extractHeader(msg: Message) =
  //   msg.getPropertyNames().asScala.map { name =>
  //     val key   = name.toString()
  //     val value = MsgProperty.make(msg.getObjectProperty(key))
  //     (key, value)
  //   }
}
