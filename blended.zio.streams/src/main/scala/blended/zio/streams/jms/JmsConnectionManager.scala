package blended.zio.streams.jms

import javax.jms._
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat

import zio._
import zio.stream._
import zio.clock._
import zio.blocking._
import zio.logging._

import blended.zio.streams._

import JmsApiObject._
import JmsApi._

object JmsConnectionManager {

  type JmsConnectionManagerService = Has[Service]

  trait Service {
    // Create a connection for a given Connection Factory with a given client id.
    def connect(
      cf: JmsConnectionFactory,
      clientId: String
    ): ZIO[JmsEnv, JMSException, JmsConnection]
    // Reconnect a given connection with an optional cause
    def reconnect(con: JmsConnection, cause: Option[Throwable]): ZIO[JmsEnv, JMSException, Unit]
    def reconnect(id: String, cause: Option[Throwable]): ZIO[JmsEnv, JMSException, Unit]
    // Close a given connection for good and don't reconnect
    def close(con: JmsConnection): ZIO[JmsEnv, JMSException, Unit]
    // shutdown the connection manager, thereby closing all current connections
    def shutdown: ZIO[JmsEnv, Nothing, Unit]
  }

  def default: ZLayer[Any, Nothing, JmsConnectionManagerService] = ZLayer.fromEffect(makeService)

  private val makeService: ZIO[Any, Nothing, Service] = for {
    cons <- Ref.make(Map.empty[String, JmsConnection])
    rec  <- Ref.make[Chunk[String]](Chunk.empty)
    s    <- Semaphore.make(1)
  } yield {

    val conMgr = new DefaultConnectionManager(rec, cons, s)

    new Service {
      override def connect(
        jmsCf: JmsConnectionFactory,
        clientId: String
      ) = conMgr.connect(jmsCf, clientId)

      override def reconnect(
        con: JmsConnection,
        cause: Option[Throwable]
      ): ZIO[ZEnv with Logging, JMSException, Unit] = conMgr.reconnect(con, cause)

      override def reconnect(id: String, cause: Option[Throwable]): ZIO[ZEnv with Logging, JMSException, Unit] =
        conMgr.reconnect(id, cause)

      override def close(con: JmsConnection): ZIO[ZEnv with Logging, JMSException, Unit] = conMgr.close(con)

      override def shutdown: ZIO[JmsEnv, Nothing, Unit] = conMgr.shutdown
    }
  }

  /**
   * A connection factory which reuses the underlying JMS connection as much as possible.
   */
  private class DefaultConnectionManager(
    // Keep a list of connection id's currently in recovery
    recovering: Ref[Chunk[String]],
    // Keep a map of current connections keyed by their connection id's
    conns: Ref[Map[String, JmsConnection]],
    // A semaphore to access the stored connections
    sem: Semaphore
  ) {

    // Just the key to find the desired connection in the cached connections
    private val conCacheId: JmsConnectionFactory => String => String = cf => clientId => s"${cf.id}-$clientId"

    // doctag<connect>
    private[jms] def connect(
      cf: JmsConnectionFactory,
      clientId: String
    ) = sem.withPermit(
      for {
        cid <- ZIO.effectTotal(conCacheId(cf)(clientId))
        con <- for {
                 cr <- getConnection(cid)
                 c  <- ZIO.fromOption(cr).orElse(checkedConnect(cf, clientId))
               } yield c
      } yield con
    )
    // end:doctag<connect>

    // doctag<reconnect>
    private[jms] def reconnect(
      con: JmsConnection,
      cause: Option[Throwable]
    ): ZIO[ZEnv with Logging, JMSException, Unit] = for {
      _  <- log.debug(s"Reconnecting JMS connection [$con]")
      cr <- getConnection(con.id)
      _  <- cr match {
              case None    => ZIO.unit
              case Some(c) => recover(c, cause)
            }
    } yield ()
    // end:doctag<reconnect>

    private[jms] def reconnect(id: String, cause: Option[Throwable]): ZIO[ZEnv with Logging, JMSException, Unit] =
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
              log.debug(
                s"Beginning recovery period for [$cid]" + t.map(c => s" , cause [${c.getMessage}]").getOrElse("")
              )
            _   <- recovering.update(r => cid +: r)
            _   <- recovering.update(_.filterNot(_ == cid)).schedule(Schedule.duration(cf.reconnectInterval))
            _   <- log.debug(s"Ending recovery period for [$cid]")
          } yield ()
        )
      }

    private def checkedConnect(
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
          _ <- log.debug(s"Adding keep Alive to $con")
          _ <- run.flatMap(missed => reconnect(con, Some(new KeepAliveException(con.id, missed)))).forkDaemon
        } yield ()
        // end:doctag<monitor>
      }

      // doctag<jmsconnect>
      for {
        _  <- log.debug(s"Connecting to [${cf.id}] with clientId [$clientId]")
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
        _  <- log.debug(s"Created [$c]")
      } yield c
      // end:doctag<jmsconnect>
    }

    private[jms] def close(c: JmsConnection) = (ZIO.uninterruptible {
      effectBlocking(c.conn.close) *> removeConnection(c)
    } <* log.debug(s"Closed [$c]")).refineOrDie { case t: JMSException => t }

    private[jms] def shutdown                = (for {
      lc <- conns.get.map(_.view.values)
      _  <- ZIO.foreach(lc)(c => close(c))
    } yield ()).catchAll(_ => ZIO.unit)

    // Get a connection from the connection map if it exists
    private def getConnection(id: String) = for {
      cm <- conns.get
      _  <- log.trace(s"Getting connection [$id]from map : $cm")
      cr  = cm.get(id)
    } yield cr

    private def addConnection(c: JmsConnection)    =
      conns.update(_ ++ Map(c.id -> c)) <* log.trace(s"Stored JMS connections : $conns")

    private def removeConnection(c: JmsConnection) =
      conns.update(_ - c.id) <* log.trace(s"Stored JMS connections after deleting [$c]: $conns")

    // Check whether a connection is currently recovering
    private def isRecovering(id: String) = recovering.get.map(_.contains(id))
  }
}
