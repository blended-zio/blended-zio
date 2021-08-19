package blended.zio.streams.jms

import javax.jms._

import zio._
import zio.blocking._
import zio.clock.Clock
import zio.duration._
import zio.logging.Logger
import zio.stream._

import blended.zio.core.RuntimeId.RuntimeIdSvc
import blended.zio.streams.FlowEnvelope
import blended.zio.streams.jms.JmsApiObject._
import blended.zio.streams.jms.JmsDestination._

object JmsApi {

  trait JmsEncoder[T] {
    def encode(v: T, js: JmsSession): ZIO[Any, JMSException, Message]
  }

  object JmsEncoder {
    implicit val stringEncoder = new JmsEncoder[String] {
      def encode(v: String, js: JmsSession): ZIO[Any, JMSException, Message] =
        effectBlocking(js.session.createTextMessage(v)).refineOrDie { case je: JMSException => je }
          .provideLayer(Blocking.live)
    }
  }

  implicit class JmsEncoderSyntax[A: JmsEncoder](inst: A) {
    def jmsEncode(js: JmsSession) = implicitly[JmsEncoder[A]].encode(inst, js)
  }

  trait JmsApiSvc {
    def createSession_(con: JmsConnection): ZIO[Any, JMSException, JmsSession]
    def closeSession_(js: JmsSession): ZIO[Any, Nothing, Unit]
    def createSession(con: JmsConnection): ZManaged[Any, JMSException, JmsSession] =
      ZManaged.make(createSession_(con))(closeSession_(_))

    def createProducer_(js: JmsSession): ZIO[Any, JMSException, JmsProducer]
    def closeProducer_(prod: JmsProducer): ZIO[Any, Nothing, Unit]
    def createProducer(js: JmsSession) = ZManaged.make(createProducer_(js))(closeProducer_(_))

    def createConsumer_(js: JmsSession, dest: JmsDestination): ZIO[Any, JMSException, JmsConsumer]
    def closeConsumer_(cons: JmsConsumer): ZIO[Any, Nothing, Unit]
    def createConsumer(js: JmsSession, dest: JmsDestination) =
      ZManaged.make(createConsumer_(js, dest))(closeConsumer_(_))

    def send[T: JmsEncoder](
      content: FlowEnvelope[_, T],
      prod: JmsProducer,
      dest: JmsDestination
    ): ZIO[Any, JMSException, Unit]

    def receive(cons: JmsConsumer): ZIO[Any, JMSException, Option[Message]]

    def jmsStream(cons: JmsConsumer) =
      ZStream.repeatEffect(receive(cons)).collect { case Some(m) => m }

    def jmsSink[T: JmsEncoder](
      prod: JmsProducer,
      dest: JmsDestination
    )                                =
      ZSink.foreach[Any, JMSException, FlowEnvelope[_, T]](c => send[T](c, prod, dest))

    def recoveringJmsStream(
      con: JmsConnection,
      retryInterval: Duration,
      dest: JmsDestination
    ): ZStream[Any, Nothing, Message]
  }

  def createSession_(con: JmsConnection) = ZIO.serviceWith[JmsApiSvc](_.createSession_(con))
  def closeSession_(js: JmsSession)      = ZIO.serviceWith[JmsApiSvc](_.closeSession_(js))
  def createSession(con: JmsConnection)  = ZIO.service[JmsApiSvc].map(_.createSession(con))

  def createProducer_(js: JmsSession)   = ZIO.serviceWith[JmsApiSvc](_.createProducer_(js))
  def closeProducer_(prod: JmsProducer) = ZIO.serviceWith[JmsApiSvc](_.closeProducer_(prod))
  def createProducer(js: JmsSession)    = ZIO.service[JmsApiSvc].map(_.createProducer(js))

  def createConsumer_(js: JmsSession, dest: JmsDestination) = ZIO.serviceWith[JmsApiSvc](_.createConsumer_(js, dest))
  def closeConsumer_(cons: JmsConsumer)                     = ZIO.serviceWith[JmsApiSvc](_.closeConsumer_(cons))
  def createConsumer(js: JmsSession, dest: JmsDestination)  = ZIO.service[JmsApiSvc].map(_.createConsumer(js, dest))

  def send[T: JmsEncoder](content: FlowEnvelope[_, T], prod: JmsProducer, dest: JmsDestination) =
    ZIO.serviceWith[JmsApiSvc](_.send(content, prod, dest))
  def receive(cons: JmsConsumer)                                                                = ZIO.serviceWith[JmsApiSvc](_.receive(cons))

  def jmsStream(cons: JmsConsumer) = ZStream.fromEffect(ZIO.service[JmsApiSvc]).map(a => a.jmsStream(cons))

  def jmsSink[T: JmsEncoder](prod: JmsProducer, dest: JmsDestination) =
    ZIO.service[JmsApiSvc].map(a => a.jmsSink(prod, dest))

  val mapException: Throwable => JMSException = t =>
    t match {
      case t: JMSException => t
      case e: Exception    =>
        val je = new JMSException(e.getMessage())
        je.setLinkedException(e)
        je
      case o               => new JMSException(o.getMessage())
    }

  final case class JmsApiImpl(
    logger: Logger[String],
    idSvc: RuntimeIdSvc
  ) extends JmsApiSvc { self =>

    override def createSession_(con: JmsConnection) =
      ((for {
        js <- withBlocking(
                con.conn.createSession(false, Session.AUTO_ACKNOWLEDGE)
              )
        id <- idSvc.nextId(con.id)
        s   = JmsSession(con, s"S-$id", js)
        _  <- logger.debug(s"Created JMS Session [$s]")
      } yield s).mapError(mapException))

    override def closeSession_(js: JmsSession) =
      (for {
        _ <- logger.debug(s"Closing JMS Session [$js")
        _ <- withBlocking(js.session.close()).catchAll(t => logException(s"Error closing JMS session [$js]", t))
      } yield ())

    override def createProducer_(js: JmsSession) = for {
      id <- idSvc.nextId(js.id)
      p  <- withBlocking(
              js.session.createProducer(null)
            ).map(prod => JmsProducer(js, s"P-$id", prod)).mapError(mapException)
      _  <- logger.debug(s"Created JMS Producer [$p]")
    } yield p

    def closeProducer_(p: JmsProducer) =
      (for {
        _ <- logger.debug(s"Closing JMS Producer [$p]")
        _ <- withBlocking(p.producer.close()).catchAll(t => logException(s"Error closing JMS Producer [$p]", t))
      } yield ())

    override def createConsumer_(js: JmsSession, dest: JmsDestination) =
      (for {
        id <- idSvc.nextId(js.id)
        d  <- dest.create(js)
        c  <- withBlocking {
                dest match {
                  case JmsDurableTopic(_, subscriberName) =>
                    js.session.createDurableSubscriber(d.asInstanceOf[Topic], subscriberName)
                  case _                                  => js.session.createConsumer(d)
                }
              }.map(cons => JmsConsumer(js, s"C-$id", dest, cons)).mapError(mapException)
        _  <- logger.debug(s"Created JMS Consumer [$c]")
      } yield c)

    override def closeConsumer_(c: JmsConsumer) =
      (for {
        _ <- logger.debug(s"Closing JMS Consumer [$c]")
        _ <- withBlocking(c.consumer.close()).catchAll(t => logException(s"Error closing JMS consumer [${c.id}]", t))
      } yield ())

    // doctag<send>
    def send[T: JmsEncoder](
      content: FlowEnvelope[_, T],
      prod: JmsProducer,
      dest: JmsDestination
    ) =
      ((for {
        msg <- content.content.jmsEncode(prod.session)
        d   <- dest.create(prod.session)
        _   <- withBlocking(prod.producer.send(d, msg))
        _   <- logger.debug(s"Message [$content] sent successfully with [$prod] to [${dest.asString}]")
      } yield ()).flatMapError { t =>
        logger.warn(s"Error sending message with [$prod] to [$dest]: [${t.getMessage()}]") *> ZIO.succeed(t)
      }.mapError(mapException))
    // end:doctag<send>

    // doctag<receive>
    def receive(cons: JmsConsumer) =
      withBlocking(Option(cons.consumer.receiveNoWait()))
        .flatMap(msg => ZIO.foreach(msg)(msg => logger.debug(s"Received [$msg] with [$cons]")) *> ZIO.succeed(msg))
        .flatMapError { t =>
          logger.warn(s"Error receiving message with [$cons] : [${t.getMessage()}]") *> ZIO.succeed(t)
        }
        .mapError(mapException)
    // end:doctag<receive>

    def recoveringJmsStream(
      con: JmsConnection,
      retryInterval: Duration,
      dest: JmsDestination
    ) = {

      val consumeUntilException: zio.Queue[Message] => JmsConsumer => ZIO[Any, JMSException, Unit] = buffer =>
        cons => jmsStream(cons).foreach(buffer.offer(_))

      val consumeForEver: zio.Queue[Message] => ZIO[Any, Nothing, Unit] = buffer => {
        val part = for {
          _ <- logger.debug(s"Trying to recover consumer for [${con.factory.id}] with destination [$dest]")
          _ <- createSession(con)
                 .use(jmsSess => createConsumer(jmsSess, dest).use(c => consumeUntilException(buffer)(c)))
        } yield ()

        part.catchAll { _ =>
          (for {
            f <- consumeForEver(buffer).schedule(Schedule.duration(retryInterval)).fork
            _ <- f.join
          } yield ()).provideLayer(Clock.live)
        }
      }

      ZStream
        .fromEffect(for {
          buffer <- zio.Queue.bounded[Message](1)
          _      <- consumeForEver(buffer).fork
        } yield buffer)
        .map(q => ZStream.repeatEffect(q.take))
        .flatten
    }

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

    private def logException(msg: => String, t: Throwable) = logger.warn(s"$msg : ${t.getMessage()}")

    private def withBlocking[A](a: => A): ZIO[Any, Throwable, A] =
      effectBlocking(a).provideLayer(Blocking.live)

  }
}
