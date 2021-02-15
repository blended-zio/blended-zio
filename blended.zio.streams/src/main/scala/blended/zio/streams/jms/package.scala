package blended.zio.streams

import javax.jms._

import zio._
import zio.logging._
import zio.blocking._
import zio.stream._
import zio.duration.Duration

import blended.zio.streams.jms.ZIOJmsConnectionManager.ZIOJmsConnectionManager

package object jms {

  def connect(
    cf: JmsConnectionFactory,
    clientId: String
  ): ZIO[ZEnv with Logging with ZIOJmsConnectionManager, JMSException, JmsConnection] = for {
    mgr <- ZIO.service[ZIOJmsConnectionManager.Service]
    con <- mgr.connect(cf, clientId)
  } yield con

  def reconnect(
    con: JmsConnection,
    cause: Option[Throwable]
  ): ZIO[ZEnv with Logging with ZIOJmsConnectionManager, JMSException, Unit] = for {
    mgr <- ZIO.service[ZIOJmsConnectionManager.Service]
    _   <- mgr.reconnect(con, cause)
  } yield ()

  /**
   * Create a connection with an effect executed whenever the underlying JMS connection is (re)created
   */
  def monitoredConnect(
    clientId: String,
    cf: JmsConnectionFactory
  )(
    onConnect: JmsConnection => ZIO[ZEnv with Logging with ZIOJmsConnectionManager, Nothing, Unit]
  ): ZIO[ZEnv with Logging with ZIOJmsConnectionManager, JMSException, JmsConnection] = for {
    con <- connect(cf, clientId)
    _   <- for {
             f <- onConnect(con).fork
             _ <- f.join
           } yield ()
  } yield con

  def createSession(con: JmsConnection) = ZManaged.make((for {
    js <- effectBlocking(
            con.conn.createSession(false, Session.AUTO_ACKNOWLEDGE)
          )
    n   = con.nextSessionName
    s   = JmsSession(con, n, js)
  } yield s).refineOrDie { case t: JMSException => t })(s =>
    for {
      _ <- log.debug(s"Closing JMS Session [$s]")
      _ <- effectBlocking(s.session.close()).orDie
    } yield ()
  )

  def createProducer(js: JmsSession) = ZManaged.make(for {
    n <- ZIO.succeed(js.nextProdName)
    p <- effectBlocking(
           js.session.createProducer(null)
         ).map(prod => JmsProducer(js, n, prod)).refineOrDie { case t: JMSException => t }
    _ <- log.debug(s"Created JMS Producer [${p.id}]")
  } yield p)(p =>
    for {
      _ <- log.debug(s"Closing JMS Producer [${p.id}]")
      _ <- effectBlocking(p.producer.close()).orDie
    } yield ()
  )

  def createConsumer(js: JmsSession, dest: JmsDestination) =
    ZManaged.make(
      for {
        n <- ZIO.succeed(js.nextConsName)
        d <- dest.create(js)
        c <- effectBlocking {
               dest match {
                 case JmsDurableTopic(_, subscriberName) =>
                   js.session.createDurableSubscriber(d.asInstanceOf[Topic], subscriberName)
                 case _                                  => js.session.createConsumer(d)
               }
             }.map(cons => JmsConsumer(js, n, dest, cons)).refineOrDie { case t: JMSException => t }
        _ <- log.debug(s"Created JMS Consumer [$c]")
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
    ZSink.foreach[ZEnv with Logging with ZIOJmsConnectionManager, JMSException, String](s => send(s, prod, dest))
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
