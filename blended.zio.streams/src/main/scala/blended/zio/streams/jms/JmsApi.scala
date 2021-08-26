package blended.zio.streams.jms

import javax.jms._

import zio._
import zio.clock.Clock
import zio.blocking._
import zio.duration._
import zio.logging.{ Logger, Logging }
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

    implicit lazy val envDecoder: JmsEncoder[FlowEnvelope[String, JmsMessageBody]] =
      new JmsEncoder[FlowEnvelope[String, JmsMessageBody]] {
        override def encode(env: FlowEnvelope[String, JmsMessageBody], js: JmsSession) =
          (effectBlocking {
            val msg = env.content match {
              case JmsMessageBody.Text(s)   => js.session.createTextMessage(s)
              case JmsMessageBody.Binary(b) =>
                val res = js.session.createBytesMessage()
                res.writeBytes(b.toArray)
                res
              case JmsMessageBody.Empty     => js.session.createMessage()
            }
            env.header.entries.foreach { case (k, v) => msg.setObjectProperty(k, v.value) }
            msg
          }.mapError {
            _ match {
              case je: JMSException => je
              case t: Exception     =>
                val res = new JMSException(t.getMessage())
                res.setLinkedException(t)
                res
              case o                => new JMSException(o.getMessage())
            }
          }).provideLayer(Blocking.live)
      }
  }

  implicit class JmsEncoderSyntax[A: JmsEncoder](inst: A) {
    def jmsEncode(js: JmsSession) = implicitly[JmsEncoder[A]].encode(inst, js)
  }

  trait JmsApiSvc {
    def createSession_(con: JmsConnection): ZIO[Any, JMSException, JmsSession]
    def closeSession_(js: JmsSession): ZIO[Any, Nothing, Unit]
    def createSession(con: JmsConnection): ZManaged[Any, JMSException, JmsSession]

    def createProducer_(js: JmsSession): ZIO[Any, JMSException, JmsProducer]
    def closeProducer_(prod: JmsProducer): ZIO[Any, Nothing, Unit]
    def createProducer(js: JmsSession): ZManaged[Any, JMSException, JmsProducer]

    def createConsumer_(js: JmsSession, dest: JmsDestination): ZIO[Any, JMSException, JmsConsumer]
    def closeConsumer_(cons: JmsConsumer): ZIO[Any, Nothing, Unit]
    def createConsumer(js: JmsSession, dest: JmsDestination): ZManaged[Any, JMSException, JmsConsumer]

    def send[T: JmsEncoder](
      content: T,
      prod: JmsProducer,
      dest: JmsDestination
    ): ZIO[Any, JMSException, Unit]

    def receive(cons: JmsConsumer): ZIO[Any, JMSException, Option[Message]]

    def jmsStream(cons: JmsConsumer): ZStream[Any, JMSException, Message]

    def jmsSink[T: JmsEncoder](
      prod: JmsProducer,
      dest: JmsDestination
    ): ZSink[Any, JMSException, T, T, Unit]

    def recoveringJmsStream(
      con: JmsConnection,
      dest: JmsDestination,
      retryInterval: Duration
    ): ZStream[Any, Nothing, Message]

    def recoveringJmsSink[T: JmsEncoder](
      con: JmsConnection,
      dest: JmsDestination,
      retryInterval: Duration
    ): ZSink[Any, Nothing, T, T, Unit]
  }

  val default: ZLayer[Logging with Has[RuntimeIdSvc], Nothing, Has[JmsApiSvc]] =
    ZIO
      .service[Logger[String]]
      .zipPar(ZIO.service[RuntimeIdSvc])
      .map { case (logger, idSvc) => JmsApiImpl(logger, idSvc) }
      .toLayer

  def createSession_(con: JmsConnection): ZIO[Has[JmsApiSvc], JMSException, JmsSession] =
    ZIO.serviceWith[JmsApiSvc](_.createSession_(con))

  def closeSession_(js: JmsSession): ZIO[Has[JmsApiSvc], Nothing, Unit] =
    ZIO.serviceWith[JmsApiSvc](_.closeSession_(js))

  def createSession(con: JmsConnection): ZManaged[Has[JmsApiSvc], JMSException, JmsSession] =
    ZManaged.accessManaged[Has[JmsApiSvc]](_.get.createSession(con))

  def createProducer_(js: JmsSession): ZIO[Has[JmsApiSvc], JMSException, JmsProducer] =
    ZIO.serviceWith[JmsApiSvc](_.createProducer_(js))

  def closeProducer_(prod: JmsProducer): ZIO[Has[JmsApiSvc], Nothing, Unit] =
    ZIO.serviceWith[JmsApiSvc](_.closeProducer_(prod))

  def createProducer(js: JmsSession): ZManaged[Has[JmsApiSvc], JMSException, JmsProducer] =
    ZManaged.accessManaged[Has[JmsApiSvc]](_.get.createProducer(js))

  def createConsumer_(js: JmsSession, dest: JmsDestination): ZIO[Has[JmsApiSvc], JMSException, JmsConsumer] =
    ZIO.serviceWith[JmsApiSvc](_.createConsumer_(js, dest))

  def closeConsumer_(cons: JmsConsumer): ZIO[Has[JmsApiSvc], Nothing, Unit] =
    ZIO.serviceWith[JmsApiSvc](_.closeConsumer_(cons))

  def createConsumer(js: JmsSession, dest: JmsDestination): ZManaged[Has[JmsApiSvc], JMSException, JmsConsumer] =
    ZManaged.accessManaged[Has[JmsApiSvc]](_.get.createConsumer(js, dest))

  def send[T: JmsEncoder](
    content: T,
    prod: JmsProducer,
    dest: JmsDestination
  ): ZIO[Has[JmsApiSvc], JMSException, Unit] =
    ZIO.serviceWith[JmsApiSvc](_.send(content, prod, dest))

  def receive(cons: JmsConsumer): ZIO[Has[JmsApiSvc], JMSException, Option[Message]] =
    ZIO.serviceWith[JmsApiSvc](_.receive(cons))

  def jmsStream(cons: JmsConsumer): ZStream[Has[JmsApiSvc], JMSException, Message] =
    ZStream.service[JmsApiSvc].flatMap(_.jmsStream(cons))

  def recoveringsJmsStream(
    con: JmsConnection,
    dest: JmsDestination,
    retryInterval: Duration
  ): ZStream[Has[JmsApiSvc], Nothing, Message] =
    ZStream.service[JmsApiSvc].flatMap(_.recoveringJmsStream(con, dest, retryInterval))

  def jmsSink[T: JmsEncoder](
    prod: JmsProducer,
    dest: JmsDestination
  ): ZSink[Has[JmsApiSvc], JMSException, T, T, Unit] =
    ZSink.accessSink[Has[JmsApiSvc]](_.get.jmsSink(prod, dest))

  def recoveringJmsSink[T: JmsEncoder](
    con: JmsConnection,
    dest: JmsDestination,
    retryInterval: Duration
  ): ZSink[Has[JmsApiSvc], Nothing, T, T, Unit] =
    ZSink.accessSink[Has[JmsApiSvc]](_.get.recoveringJmsSink[T](con, dest, retryInterval))

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

    override def createSession(con: JmsConnection): ZManaged[Any, JMSException, JmsSession] =
      ZManaged.make(createSession_(con))(js => closeSession_(js))

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

    def createProducer(js: JmsSession): ZManaged[Any, JMSException, JmsProducer] =
      ZManaged.make(createProducer_(js))(p => closeProducer_(p))

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

    def createConsumer(js: JmsSession, dest: JmsDestination): ZManaged[Any, JMSException, JmsConsumer] =
      ZManaged.make(createConsumer_(js, dest))(c => closeConsumer_(c))

    // doctag<send>
    def send[T: JmsEncoder](
      content: T,
      prod: JmsProducer,
      dest: JmsDestination
    ) =
      ((for {
        msg <- content.jmsEncode(prod.session)
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

    def jmsStream(cons: JmsConsumer): ZStream[Any, JMSException, Message] =
      ZStream.repeatEffect(receive(cons)).collect { case Some(m) => m }

    def recoveringJmsStream(
      con: JmsConnection,
      dest: JmsDestination,
      retryInterval: Duration
    ): ZStream[Any, Nothing, Message] = {
      val cons: ZManaged[Any, JMSException, JmsConsumer] = createSession(con).flatMap(js => createConsumer(js, dest))
      val partial: ZStream[Any, JMSException, Message]   = ZStream.managed(cons).flatMap(cons => jmsStream(cons))

      val complete =
        partial
          .retry(Schedule.duration(retryInterval))
          .provideLayer(Clock.live)
      complete.catchAll(_ => ZStream.empty)
    }

    def jmsSink[T: JmsEncoder](prod: JmsProducer, dest: JmsDestination): ZSink[Any, JMSException, T, T, Unit] =
      ZSink.foreach[Any, JMSException, T](c => send[T](c, prod, dest))

    def recoveringJmsSink[T: JmsEncoder](
      con: JmsConnection,
      dest: JmsDestination,
      retryInterval: Duration
    ): ZSink[Any, Nothing, T, T, Unit] = ???
    //   {
    //   def produceOne(p: JmsProducer) = buffer.take.flatMap { s: FlowEnvelope[_, T] =>
    //     JmsApi.send(s, p, dest, encode)
    //   }
    //   def produceForever: ZIO[JmsEnv, Nothing, Unit] = {
    //     val part = for {
    //       _      <- log.debug(s"Trying to recover producer for [${factory.id}] with destination [$dest]")
    //       conMgr <- ZIO.service[JmsConnectionManager.JmsConnectionMangerSvc]
    //       con    <- conMgr.connect(factory, clientId)
    //       _      <- createSession(con).use { jmsSess =>
    //                   createProducer(jmsSess).use { p =>
    //                     for {
    //                       f <- produceOne(p).forever.fork
    //                       _ <- f.join
    //                     } yield ()
    //                   }
    //                 }
    //     } yield ()
    //     part.catchAll { _ =>
    //       for {
    //         f <- produceForever.schedule(Schedule.duration(retryInterval)).fork
    //         _ <- f.join
    //       } yield ()
    //     }
    //   }
    //   for {
    //     _ <- produceForever.fork
    //     s <- ZIO.succeed(ZSink.foreach(msg => buffer.offer(msg)))
    //   } yield s
    // }

    private def logException(msg: => String, t: Throwable) = logger.warn(s"$msg : ${t.getMessage()}")

    private def withBlocking[A](a: => A): ZIO[Any, Throwable, A] =
      effectBlocking(a).provideLayer(Blocking.live)

  }
}
