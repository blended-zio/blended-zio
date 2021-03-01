package blended.zio.streams.jms

import javax.jms._

import zio._
import zio.logging._
import zio.blocking._
import zio.stream._
import zio.duration.Duration

import blended.zio.core.RuntimeId
import RuntimeId.RuntimeIdService

import JmsConnectionManager._
import JmsDestination._
import JmsApiObject._

object JmsApi {

  type JmsEnv = ZEnv with Logging with JmsConnectionManagerService with RuntimeIdService

  val defaultJmsEnv: ZLayer[Any, Nothing, Logging] => ZLayer[
    Any,
    Nothing,
    Any with Logging with JmsConnectionManagerService with RuntimeIdService
  ] =
    logging => logging ++ JmsConnectionManager.Service.make ++ RuntimeId.Service.make

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

  def createSession(con: JmsConnection) = ZManaged.make((for {
    rid <- ZIO.service[RuntimeId.Service]
    js  <- effectBlocking(
             con.conn.createSession(false, Session.AUTO_ACKNOWLEDGE)
           )
    id  <- rid.nextId(con.id)
    s    = JmsSession(con, s"S-$id", js)
  } yield s).refineOrDie { case t: JMSException => t })(s =>
    for {
      _ <- log.debug(s"Closing JMS Session [$s]")
      _ <- effectBlocking(s.session.close()).orDie
    } yield ()
  )

  def createProducer(js: JmsSession) = ZManaged.make(for {
    rid <- ZIO.service[RuntimeId.Service]
    id  <- rid.nextId(js.id)
    p   <- effectBlocking(
             js.session.createProducer(null)
           ).map(prod => JmsProducer(js, s"P-$id", prod)).refineOrDie { case t: JMSException => t }
    _   <- log.debug(s"Created JMS Producer [${p.id}]")
  } yield p)(p =>
    for {
      _ <- log.debug(s"Closing JMS Producer [${p.id}]")
      _ <- effectBlocking(p.producer.close()).orDie
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
               }.map(cons => JmsConsumer(js, s"C-$id", dest, cons)).refineOrDie { case t: JMSException => t }
        _   <- log.debug(s"Created JMS Consumer [$c]")
      } yield c
    )(c =>
      for {
        _ <- log.debug(s"Closing Consumer [$c]")
        _ <- effectBlocking(c.consumer.close()).orDie
      } yield ()
    )

  // doctag<send>
  def send(
    text: String,
    prod: JmsProducer,
    dest: JmsDestination
  ) = (for {
    msg <- effectBlocking(prod.session.session.createTextMessage(text))
    d   <- dest.create(prod.session)
    _   <- effectBlocking(prod.producer.send(d, msg))
    _   <- log.debug(s"Message [$text] sent successfully with [$prod] to [${dest.asString}]")
  } yield ()).flatMapError { t =>
    log.warn(s"Error sending message with [$prod] to [$dest]: [${t.getMessage()}]") *> ZIO.succeed(t)
  }.refineOrDie { case t: JMSException => t }
  // end:doctag<send>

  // doctag<receive>
  def receive(cons: JmsConsumer) = (for {
    msg <- effectBlocking(Option(cons.consumer.receiveNoWait()))
    _   <- if (msg.isDefined) log.debug(s"Received [$msg] with [$cons]") else ZIO.unit
  } yield msg).flatMapError { t =>
    log.warn(s"Error receiving message with [$cons] : [${t.getMessage()}]") *> ZIO.succeed(t)
  }.refineOrDie { case t: JMSException => t }
  // end:doctag<receive>

  // doctag<stream>
  def jmsStream(cons: JmsConsumer) =
    ZStream.repeatEffect(receive(cons)).collect { case Some(s) => s }
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
  def jmsSink(
    prod: JmsProducer,
    dest: JmsDestination
  ) =
    ZSink.foreach[ZEnv with Logging with JmsConnectionManagerService, JMSException, String](s => send(s, prod, dest))
  // end:doctag<sink>

  def recoveringJmsSink(
    cf: JmsConnectionFactory,
    clientId: String,
    dest: JmsDestination,
    retryInterval: Duration
  ) = for {
    sinkFact <- RecoveringJmsSink.make(cf, clientId)
    s        <- sinkFact.sink(dest, retryInterval)
  } yield s
}
