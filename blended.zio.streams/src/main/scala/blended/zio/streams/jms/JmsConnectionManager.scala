package blended.zio.streams.jms

import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import javax.jms._

import zio._
import zio.blocking._
import zio.clock._
import zio.logging._
import zio.stream._

import blended.zio.streams._
import blended.zio.streams.jms.JmsApi._
import blended.zio.streams.jms.JmsApiObject._

object JmsConnectionManager {

  trait JmsConnectionMangerSvc {
    // Create a connection for a given Connection Factory with a given client id.
    def connect(
      cf: JmsConnectionFactory,
      clientId: String
    ): ZIO[Any, JMSException, JmsConnection]
    // Reconnect a given connection with an optional cause
    def reconnect(con: JmsConnection, cause: Option[Throwable]): ZIO[Any, JMSException, Unit]
    def reconnect(id: String, cause: Option[Throwable]): ZIO[Any, JMSException, Unit]
    // Close a given connection for good and don't reconnect
    def close(con: JmsConnection): ZIO[Any, JMSException, Unit]
    // shutdown the connection manager, thereby closing all current connections
    def shutdown: ZIO[Any, Nothing, Unit]
  }

  lazy val live = for {
    clock  <- ZIO.environment[Clock]
    logger <- ZIO.service[Logger[String]]
    cons   <- Ref.make(Map.empty[String, JmsConnection])
    rec    <- Ref.make[Chunk[String]](Chunk.empty)
    s      <- Semaphore.make(1)
  } yield DefaultConnectionManager(clock, logger, rec, cons, s)

  /**
   * A connection factory which reuses the underlying JMS connection as much as possible.
   */
  final case class DefaultConnectionManager private (
    clock: Clock,
    logger: Logger[String],
    // Keep a list of connection id's currently in recovery
    recovering: Ref[Chunk[String]],
    // Keep a map of current connections keyed by their connection id's
    conns: Ref[Map[String, JmsConnection]],
    // A semaphore to access the stored connections
    sem: Semaphore
  ) extends JmsConnectionMangerSvc {

    // Just the key to find the desired connection in the cached connections
    private val conCacheId: JmsConnectionFactory => String => String = cf => clientId => s"${cf.id}-$clientId"

    // doctag<connect>
    override def connect(
      cf: JmsConnectionFactory,
      clientId: String
    ) = sem.withPermit(
      for {
        cid <- ZIO.effectTotal(conCacheId(cf)(clientId))
        con <- getConnection(cid).orElse(checkedConnect(cf, clientId).provideLayer(Blocking.live ++ Clock.live))
      } yield con
    )
    // end:doctag<connect>

    // doctag<reconnect>
    private[jms] def reconnect(
      con: JmsConnection,
      cause: Option[Throwable]
    ) = for {
      _  <- logger.debug(s"Reconnecting JMS connection [$con]")
      cr <- getConnection(con.id)
      _  <- cr match {
              case None    => ZIO.unit
              case Some(c) => recover(c, cause).provideLayer(Clock.live)
            }
    } yield ()
    // end:doctag<reconnect>

    private[jms] def reconnect(id: String, cause: Option[Throwable]) =
      for {
        c <- getConnection(id)
        _ <- c match {
               case None    => ZIO.unit
               case Some(c) => reconnect(c, cause)
             }
      } yield ()

    // doctag<recover>
    private def recover(
      c: JmsConnection,
      t: Option[Throwable]
    ) = for {
      _ <- close(c)
      _ <- scheduleRecover(c.factory, c.clientId, t)
    } yield ()

    private def scheduleRecover(
      cf: JmsConnectionFactory,
      clientId: String,
      t: Option[Throwable]
    ) =
      ZIO.effectTotal(conCacheId(cf)(clientId)).flatMap { cid =>
        ZIO.ifM(getConnection(cid).map(_.isDefined))(
          ZIO.unit,
          for {
            cid <- ZIO.effectTotal(conCacheId(cf)(clientId))
            _   <-
              logger.debug(
                s"Beginning recovery period for [$cid]" + t.map(c => s" , cause [${c.getMessage}]").getOrElse("")
              )
            _   <- recovering.update(r => cid +: r)
            _   <- recovering.update(_.filterNot(_ == cid)).schedule(Schedule.duration(cf.reconnectInterval))
            _   <- logger.debug(s"Ending recovery period for [$cid]")
          } yield ()
        )
      }

    def checkedConnect(
      cf: JmsConnectionFactory,
      clientId: String
    ) = for {
      cid <- ZIO.effectTotal(conCacheId(cf)(clientId))
      con <- ZIO.ifM(isRecovering(cid))(
               ZIO.fail(new JMSException(s"Connection factory [$cid] is in recovery")),
               doConnect(cf, clientId)
             )
    } yield con
    // end:doctag<recover>

    private def doConnect(
      cf: JmsConnectionFactory,
      clientId: String
    ) = {

      def addKeepAlive(con: JmsConnection, keepAlive: JmsKeepAliveMonitor) = {

        // doctag<sender>
        val startKeepAliveSender = {

          val sdf = new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss:SSS")

          val stream = ZStream
            .fromSchedule(Schedule.spaced(keepAlive.interval))
            .mapM(_ =>
              currentTime(TimeUnit.MILLISECONDS)
                .map(t => s"KeepAlive ($con) : ${sdf.format(t)}")
                .map(s => FlowEnvelope.make(s))
            )

          createSession(con).use(jmsSess =>
            createProducer(jmsSess).use(prod => stream.run(jmsSink(prod, keepAlive.dest, stringEnvelopeEncoder)))
          )
        }
        // end:doctag<sender>

        // doctag<receiver>
        def startKeepAliveReceiver(kam: KeepAliveMonitor) = createSession(con).use { jmsSess =>
          createConsumer(jmsSess, keepAlive.dest).use(cons => jmsStream(cons).foreach(_ => kam.alive))
        }
        // end:doctag<receiver>

        // doctag<monitor>
        val run = for {
          kam  <- DefaultKeepAliveMonitor.make(s"${con.id}-KeepAlive", keepAlive.allowed)
          send <- startKeepAliveSender.fork
          rec  <- startKeepAliveReceiver(kam).fork
          _    <- kam.run(keepAlive.interval)
          _    <- send.interrupt *> rec.interrupt
          c    <- kam.current
        } yield (c)

        for {
          _ <- logger.debug(s"Adding keep Alive to $con")
          _ <- run.flatMap(missed => reconnect(con, Some(new KeepAliveException(con.id, missed)))).forkDaemon
        } yield ()
        // end:doctag<monitor>
      }

      // doctag<jmsconnect>
      for {
        _  <- logger.debug(s"Connecting to [${cf.id}] with clientId [$clientId]")
        jc <- effectBlocking {
                val jmsConn: Connection = cf.credentials match {
                  case Some(c) => cf.factory.createConnection(c._1, c._2)
                  case None    => cf.factory.createConnection()
                }
                jmsConn.setClientID(clientId)
                jmsConn.start()
                jmsConn
              }.flatMapError { t =>
                for {
                  _ <- scheduleRecover(cf, clientId, Some(t))
                } yield t
              }.refineOrDie { case t: JMSException => t }
        c   = JmsConnection(cf, jc, clientId)
        _  <- ZIO.foreach(cf.keepAlive)(ka => addKeepAlive(c, ka))
        _  <- addConnection(c)
        _  <- logger.debug(s"Created [$c]")
      } yield c
      // end:doctag<jmsconnect>
    }

    private[jms] def close(c: JmsConnection) = (ZIO.uninterruptible {
      effectBlocking(c.conn.close) *> removeConnection(c)
    } <* logger.debug(s"Closed [$c]")).provideLayer(Blocking.live).refineOrDie { case t: JMSException => t }

    private[jms] def shutdown                = (for {
      lc <- conns.get.map(_.view.values)
      _  <- ZIO.foreach(lc)(c => close(c))
    } yield ()).catchAll(_ => ZIO.unit)

    // Get a connection from the connection map if it exists
    private def getConnection(id: String) = for {
      cm <- conns.get
      _  <- logger.trace(s"Getting connection [$id]from map : $cm")
      cr  = cm.get(id)
    } yield cr

    private def addConnection(c: JmsConnection)    =
      conns.update(_ ++ Map(c.id -> c)) <* logger.trace(s"Stored JMS connections : $conns")

    private def removeConnection(c: JmsConnection) =
      conns.update(_ - c.id) <* logger.trace(s"Stored JMS connections after deleting [$c]: $conns")

    // Check whether a connection is currently recovering
    private def isRecovering(id: String) = recovering.get.map(_.contains(id))
  }
}
