package blended.zio.streams.jms

import javax.jms._

import scala.jdk.CollectionConverters._

import zio._
import zio.logging.{ Logging, Logger }

import blended.zio.streams._
import blended.zio.streams.jms.JmsApi._
import blended.zio.streams.jms.JmsConnectionManager._
import blended.zio.streams.jms.JmsApiObject._

object JmsEndpoint {

  final private case class JmsEndpoint private (
    cf: JmsApiObject.JmsConnectionFactory,
    clientId: String,
    dest: JmsDestination,
    receive: Boolean,
    selector: Option[JmsMessageSelector]
  ) {
    val id = s"JmsEndpoint-${cf.id}-${clientId}-${dest}"
  }

  // Should return ZLayer ??
  def make(
    cf: JmsApiObject.JmsConnectionFactory,
    clientId: String,
    dest: JmsDestination,
    receive: Boolean = true,
    selector: Option[JmsMessageSelector] = None
  ): ZManaged[Logging with Has[JmsApiSvc] with Has[JmsConnectionManagerSvc], Throwable, Endpoint[
    String,
    JmsMessageBody
  ]] = (for {
    logger <- ZIO.service[Logger[String]]
    conMgr <- ZIO.service[JmsConnectionManagerSvc]
    jmsApi <- ZIO.service[JmsApiSvc]
    ep     <- ZIO.effectTotal(JmsEndpoint(cf, clientId, dest, receive, selector))
    con    <- connector(ep, conMgr, jmsApi, logger)
    ep     <- Endpoint.make(ep.id, con, Endpoint.defaultEndpointConfig)
    _      <- ep.connect
  } yield ep).toManaged(ep => ep.disconnect.ignore)

  private def connector(
    ep: JmsEndpoint,
    conMgr: JmsConnectionManagerSvc,
    jmsApi: JmsApiSvc,
    logger: Logger[String]
  ): ZIO[Any, Throwable, Connector[String, JmsMessageBody]] =
    for {
      state <- RefM.make[Option[JmsEndpointState]](None)
      con    = new JmsConnector(ep, conMgr, jmsApi, logger, state)
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
    conMgr: JmsConnectionManagerSvc,
    jmsApi: JmsApiSvc,
    logger: Logger[String],
    state: RefM[Option[JmsEndpointState]]
  ) {

    import blended.zio.streams.jms.JmsApi.JmsEncoder._

    def start = state.update {
      _ match {
        case None        =>
          for {
            con  <- conMgr.connect(ep.cf, ep.clientId)
            sess <- jmsApi.createSession_(con)
            cons <- if (ep.receive) jmsApi.createConsumer_(sess, ep.dest).map(Some(_)) else ZIO.none
            prod <- jmsApi.createProducer_(sess)
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
                   case Some(c) => jmsApi.closeConsumer_(c)
                 }
            _ <- jmsApi.closeProducer_(v.producer)
            _ <- jmsApi.closeSession_(v.session)
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
                        case Some(c) => jmsApi.receive(c)
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
               case Some(v) =>
                 jmsApi.send[FlowEnvelope[String, JmsMessageBody]](env, v.producer, ep.dest)(envDecoder) *> ZIO
                   .effectTotal(env)
             }
    } yield res

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
