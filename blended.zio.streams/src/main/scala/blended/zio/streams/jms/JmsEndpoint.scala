package blended.zio.streams.jms

import javax.jms._

import scala.jdk.CollectionConverters._

import zio._
import zio.logging.{ Logging, Logger }

import blended.zio.streams._
import blended.zio.streams.jms.JmsApi._
import blended.zio.streams.jms.JmsApiObject._

object JmsEndpoint {

  final private case class JmsEndpoint private (
    cf: JmsApiObject.JmsConnectionFactory,
    clientId: String,
    dest: JmsDestination,
    receive: Boolean,
    selector: Option[JmsMessageSelector]
  ) {
    val id = s"Endpoint-${cf.id}-${clientId}-${dest}"
  }

  // Should return ZLayer ??
  def make(
    cf: JmsApiObject.JmsConnectionFactory,
    clientId: String,
    dest: JmsDestination,
    receive: Boolean = true,
    selector: Option[JmsMessageSelector] = None
  ): ZManaged[Logging, Throwable, Endpoint[String, JmsMessageBody]] = (for {
    logger <- ZIO.service[Logger[String]]
    ep     <- ZIO.effectTotal(JmsEndpoint(cf, clientId, dest, receive, selector))
    con    <- connector(ep, logger)
    ep     <- Endpoint.make(ep.id, con, Endpoint.defaultEndpointConfig)
    _      <- ep.connect
  } yield ep).toManaged(ep => ep.disconnect.catchAll(_ => ZIO.unit))

  private def connector(
    ep: JmsEndpoint,
    logger: Logger[String]
  ): ZIO[Any, Throwable, Connector[String, JmsMessageBody]] =
    for {
      state <- RefM.make[Option[JmsEndpointState]](None)
      con    = new JmsConnector(ep, logger, state)
    } yield new Connector[String, JmsMessageBody] {
      override def start: ZIO[Any, Throwable, Unit] = con.start

      override def stop: ZIO[Any, Throwable, Unit] = con.stop

      override def nextEnvelope: ZIO[Any, Throwable, Option[FlowEnvelope[String, JmsMessageBody]]] =
        con.nextEnvelope_?

      override def sendEnvelope(
        env: FlowEnvelope[String, JmsMessageBody]
      ): ZIO[Any, Throwable, FlowEnvelope[String, JmsMessageBody]] =
        con.sendEnvelope(env)
    }

  private case class JmsEndpointState(
    session: JmsSession,
    consumer: Option[JmsConsumer],
    producer: JmsProducer
  ) {
    override def toString(): String = s"JmsEndpointState($session, $consumer, $producer)"
  }

  sealed private class JmsConnector(
    ep: JmsEndpoint,
    logger: Logger[String],
    state: RefM[Option[JmsEndpointState]]
  ) {

    def start = state.update {
      _ match {
        case None        =>
          for {
            con  <- JmsApi.connect(ep.cf, ep.clientId)
            sess <- JmsApi.createSession_(con)
            cons <- if (ep.receive) JmsApi.createConsumer_(sess, ep.dest).map(Some(_)) else ZIO.none
            prod <- JmsApi.createProducer_(sess)
            state = JmsEndpointState(sess, cons, prod)
            _    <- logger.info(s"Created JMS endpoint state [$state]")
          } yield Some(state)
        case v @ Some(_) => ZIO.succeed(v)
      }
    }

    def stop = state.update {
      _ match {
        case Some(v) =>
          for {
            _ <- logger.info(s"Closing JMS endpoint state [$v]")
            _ <- v.consumer match {
                   case None    => ZIO.unit
                   case Some(c) => JmsApi.closeConsumer_(c)
                 }
            _ <- JmsApi.closeProducer_(v.producer)
            _ <- JmsApi.closeSession_(v.session)
          } yield None
        case None    => ZIO.none
      }
    }

    def nextEnvelope_? = for {
      cs       <- state.get
      maybeMsg <- cs match {
                    case None    => ZIO.none
                    case Some(v) =>
                      v.consumer match {
                        case None    => ZIO.none
                        case Some(c) => JmsApi.receive(c)
                      }
                  }
      maybeEnv  = maybeMsg.map { m =>
                    val h = extractHeader(m)
                    val c = (m match {
                      case tm: TextMessage  => JmsMessageBody.Text(tm.getText())
                      case bm: BytesMessage => JmsMessageBody.Binary(extractBytes(bm))
                      case _                => JmsMessageBody.Empty
                    }).asInstanceOf[JmsMessageBody]
                    FlowEnvelope.make(m.getJMSMessageID(), c).addHeader(h)
                  }
    } yield maybeEnv

    def sendEnvelope(env: FlowEnvelope[String, JmsMessageBody]) = for {
      cs  <- state.get
      res <- cs match {
               case None    => ZIO.fail(new IllegalStateException(s"Endpoint [${ep.id}] is currently not connected"))
               case Some(v) => JmsApi.send[JmsMessageBody](env, v.producer, ep.dest, encoder) *> ZIO.effectTotal(env)
             }
    } yield res

    private val encoder: JmsEncoder[JmsMessageBody] = p =>
      env =>
        (effectBlocking {
          val msg = env.content match {
            case JmsMessageBody.Text(s)   => p.session.session.createTextMessage(s)
            case JmsMessageBody.Binary(b) =>
              val res = p.session.session.createBytesMessage()
              res.writeBytes(b.toArray)
              res
            case JmsMessageBody.Empty     => p.session.session.createMessage()
          }
          env.header.entries.foreach { case (k, v) => msg.setObjectProperty(k, v.value) }
          msg
        }).mapError {
          _ match {
            case je: JMSException => je
            case t: Exception     =>
              val res = new JMSException(t.getMessage())
              res.setLinkedException(t)
              res
            case o                => new JMSException(o.getMessage())
          }
        }

    private val extractBytes: BytesMessage => Chunk[Byte] = { bm =>
      val buf = new Array[Byte](bm.getBodyLength().toInt)
      bm.readBytes(buf)
      Chunk.fromArray(buf)
    }

    private val extractHeader: Message => EnvelopeHeader = { m =>
      EnvelopeHeader(
        m.getPropertyNames()
          .asScala
          .map { n =>
            (n.toString(), m.getObjectProperty(n.toString()))
          }
          .map { case (k, v) => (k, MsgProperty.make(v)) }
          .collect { case (k, Some(v)) => (k, v) }
          .toMap
      )
    }
  }
}
