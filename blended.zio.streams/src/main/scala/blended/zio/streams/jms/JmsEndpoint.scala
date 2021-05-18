package blended.zio.streams.jms

import javax.jms._
import scala.jdk.CollectionConverters._

import blended.zio.streams._

import zio._
import zio.blocking._

import JmsApi._
import JmsApiObject._

object JmsEndpoint {

  final private case class JmsEndpoint private (
    cf: JmsApiObject.JmsConnectionFactory,
    clientId: String,
    dest: JmsDestination,
    selector: Option[JmsMessageSelector]
  ) {
    val id = s"Endpoint-${cf.id}-${clientId}-${dest}"
  }

  // Should return ZLayer ??
  def make(
    cf: JmsApiObject.JmsConnectionFactory,
    clientId: String,
    dest: JmsDestination,
    selector: Option[JmsMessageSelector] = None
  ): ZManaged[JmsApi.JmsEnv, Throwable, Endpoint[String, JmsMessageBody]] = (for {
    ep  <- ZIO.effectTotal(JmsEndpoint(cf, clientId, dest, selector))
    con <- connector(ep)
    ep  <- Endpoint.make(ep.id, con, Endpoint.defaultEndpointConfig)
    _   <- ep.connect
  } yield ep).toManaged(ep => ep.disconnect.catchAll(_ => ZIO.unit))

  private def connector(ep: JmsEndpoint): ZIO[JmsApi.JmsEnv, Throwable, Connector[JmsEnv, String, JmsMessageBody]] =
    for {
      state <- RefM.make[Option[JmsEndpointState]](None)
      con    = new JmsConnector(ep, state)
    } yield new Connector[JmsEnv, String, JmsMessageBody] {
      override def start: ZIO[JmsEnv, Throwable, Unit] = con.start

      override def stop: ZIO[JmsEnv, Throwable, Unit] = con.stop

      override def nextEnvelope: ZIO[JmsEnv, Throwable, Option[FlowEnvelope[String, JmsMessageBody]]] =
        con.nextEnvelope_?

      override def sendEnvelope(
        env: FlowEnvelope[String, JmsMessageBody]
      ): ZIO[JmsEnv, Throwable, FlowEnvelope[String, JmsMessageBody]] =
        con.sendEnvelope(env)
    }

  private case class JmsEndpointState(
    session: JmsSession,
    consumer: JmsConsumer,
    producer: JmsProducer
  )

  object JmsConnector {

    // Create a JmsConnector as a Layer ??
    // def make()

  }

  sealed private class JmsConnector(
    ep: JmsEndpoint,
    state: RefM[Option[JmsEndpointState]]
  ) {

    def start = state.update {
      _ match {
        case None        =>
          for {
            con  <- JmsApi.connect(ep.cf, ep.clientId)
            sess <- JmsApi.createSession_(con)
            cons <- JmsApi.createConsumer_(sess, ep.dest)
            prod <- JmsApi.createProducer_(sess)
          } yield Some(JmsEndpointState(sess, cons, prod))
        case v @ Some(_) => ZIO.succeed(v)
      }
    }

    def stop = state.update {
      _ match {
        case Some(v) =>
          JmsApi.closeConsumer_(v.consumer) *> JmsApi.closeProducer_(v.producer) *> JmsApi.closeSession_(
            v.session
          ) *> ZIO.none
        case None    => ZIO.succeed(None)
      }
    }

    def nextEnvelope_? = for {
      cs       <- state.get
      maybeMsg <- cs match {
                    case None    => ZIO.none
                    case Some(v) => JmsApi.receive(v.consumer)
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
