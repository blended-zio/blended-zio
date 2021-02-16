package blended.zio.streams.jms

import javax.jms._
import java.util.concurrent.TimeUnit

import zio._
import zio.stream._
import zio.clock._
import zio.blocking._
import zio.logging._

import blended.zio.streams.{ KeepAliveMonitor, DefaultKeepAliveMonitor }
import java.text.SimpleDateFormat
import blended.zio.streams.KeepAliveException

object ZIOJmsConnectionManager {

  type ZIOJmsConnectionManager = Has[Service]

  trait Service {
    // Create a connection for a given Connection Factory with a given client id.
    def connect(
      cf: JmsConnectionFactory,
      clientId: String
    ): ZIO[ZEnv with Logging with ZIOJmsConnectionManager, JMSException, JmsConnection]
    // Reconnect a given connection with an optional cause
    def reconnect(con: JmsConnection, cause: Option[Throwable]): ZIO[ZEnv with Logging, JMSException, Unit]
    def reconnect(id: String, cause: Option[Throwable]): ZIO[ZEnv with Logging, JMSException, Unit]
    // Close a given connection for good and don't reconnect
    def close(con: JmsConnection): ZIO[ZEnv with Logging, JMSException, Unit]
    // shutdown the connection manager, thereby closing all current connections
    def shutdown: ZIO[ZEnv with Logging, Nothing, Unit]
  }

  object Service {

    def make: ZLayer[Any, Nothing, ZIOJmsConnectionManager] = ZLayer.fromEffect(makeService)

    private val makeService: ZIO[Any, Nothing, Service] = for {
      cons <- Ref.make(Map.empty[String, JmsConnection])
      rec  <- Ref.make(List.empty[String])
      s    <- Semaphore.make(1)
    } yield {

      val conMgr = new DefaultConnectionManager {
        override private[jms] val conns      = cons
        override private[jms] val recovering = rec
        override private[jms] val sem        = s
      }

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

        override def shutdown: ZIO[ZEnv with Logging, Nothing, Unit] = conMgr.shutdown
      }
    }

    /**
     * A connection factory which reuses the underlying JMS connection as much as possible.
     */
    sealed abstract private class DefaultConnectionManager {

      // Keep a list of connection id's currently in recovery
      private[jms] val recovering: Ref[List[String]]
      // Keep a map of current connections keyed by their connection id's
      private[jms] val conns: Ref[Map[String, JmsConnection]]
      // A semaphore to access the connection semaphores
      private[jms] val sem: Semaphore

      // doctag<connect>
      private[jms] def connect(
        cf: JmsConnectionFactory,
        clientId: String
      ) = {

        val cid = cf.connId(clientId)

        sem.withPermit(
          for {
            con <- for {
                     cr <- getConnection(cid)
                     c  <- ZIO.fromOption(cr).orElse(checkedConnect(cf, clientId))
                   } yield c
          } yield con
        )
      }
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
      ) = {

        val cid = cf.connId(clientId)

        ZIO.ifM(getConnection(cid).map(_.isDefined))(
          ZIO.unit,
          for {
            _ <-
              log.debug(
                s"Beginning recovery period for [$cid]" + t.map(c => s" , cause [${c.getMessage}]").getOrElse("")
              )
            _ <- recovering.update(r => cid :: r)
            f <- recovering.update(_.filterNot(_ == cid)).schedule(Schedule.duration(cf.reconnectInterval)).fork
            _ <- f.join
            _ <- log.debug(s"Ending recovery period for [$cid]")
          } yield ()
        )
      }

      private def checkedConnect(
        cf: JmsConnectionFactory,
        clientId: String
      ) = {

        val cid = cf.connId(clientId)

        ZIO.ifM(isRecovering(cid))(
          ZIO.fail(new JMSException(s"Connection factory [$cid] is in recovery")),
          for {
            c <- doConnect(cf, clientId)
          } yield c
        )
      }
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
              )

            createSession(con).use(jmsSess =>
              createProducer(jmsSess).use(prod => stream.run(jmsSink(prod, keepAlive.dest)))
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
                  val jmsConn: Connection = cf.factory.createConnection()
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

      private def addConnection(c: JmsConnection) = for {
        cm <- conns.updateAndGet(_ ++ Map(c.id -> c))
        _  <- log.trace(s"Stored JMS connections : $cm")
      } yield ()

      private def removeConnection(c: JmsConnection) = for {
        cm <- conns.updateAndGet(_ - c.id)
        _  <- log.trace(s"Stored JMS connections after deleting [$c]: $cm")
      } yield ()

      // Check whether a connection is currently recovering
      private def isRecovering(id: String) = recovering.get.map(_.contains(id))
    }
  }
}
