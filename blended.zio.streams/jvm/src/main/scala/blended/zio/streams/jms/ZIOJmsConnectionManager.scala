package blended.zio.streams.jms

import javax.jms._

import zio._
import zio.blocking._
import zio.logging._

import blended.zio.streams.SingletonService

object ZIOJmsConnectionManager {

  type ZIOJmsConnectionManager = Has[Service]

  trait Service {
    // Create a connection for a given Connection Factory with a given client id.
    def connect(cf: JmsConnectionFactory, clientId: String): ZIO[ZEnv with Logging, JMSException, JmsConnection]
    // Reconnect a given connection with an optional cause and a given interval after which the reconnect should happen
    def reconnect(con: JmsConnection, cause: Option[Throwable]): ZIO[ZEnv with Logging, JMSException, Unit]
    def reconnect(id: String, cause: Option[Throwable]): ZIO[ZEnv with Logging, JMSException, Unit]
    // Close a given connection for good and don't reconnect
    def close(con: JmsConnection): ZIO[ZEnv with Logging, JMSException, Unit]
    // shutdown the connection manager, thereby closing all current connections
    def shutdown: ZIO[ZEnv with Logging, Nothing, Unit]
  }

  object Service {

    // doctag<singleton>
    val singleton: ZIO[Any, Nothing, SingletonService[Service]] =
      SingletonService.fromEffect(createLive)

    def live(s: SingletonService[Service]): ZLayer[Any, Nothing, ZIOJmsConnectionManager] = ZLayer.fromEffect(s.service)
    // end:doctag<singleton>

    private def createLive: ZIO[Any, Nothing, Service] = for {
      cons <- Ref.make(Map.empty[String, JmsConnection])
      rec  <- Ref.make(List.empty[String])
      s    <- Semaphore.make(1)
    } yield {

      val cf = new DefaultConnectionManager {
        override private[jms] val conns      = cons
        override private[jms] val recovering = rec
        override private[jms] val sem        = s
      }

      new Service {
        override def connect(
          jmsCf: JmsConnectionFactory,
          clientId: String
        ): ZIO[zio.ZEnv with zio.logging.Logging, JMSException, JmsConnection]                                   = cf.connect(jmsCf, clientId)
        override def reconnect(
          con: JmsConnection,
          cause: Option[Throwable]
        ): ZIO[ZEnv with Logging, JMSException, Unit]                                                            = cf.reconnect(con, cause)
        override def reconnect(id: String, cause: Option[Throwable]): ZIO[ZEnv with Logging, JMSException, Unit] =
          cf.reconnect(id, cause)
        override def close(con: JmsConnection): ZIO[ZEnv with Logging, JMSException, Unit]                       = cf.close(con)
        override def shutdown: ZIO[ZEnv with Logging, Nothing, Unit]                                             = cf.shutdown
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
      ): ZIO[ZEnv with Logging, JMSException, JmsConnection] = {

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
      ): ZIO[ZEnv with Logging, JMSException, Unit] = for {
        _ <- close(c)
        _ <- scheduleRecover(c.factory, c.clientId, t)
      } yield ()

      private def scheduleRecover(
        cf: JmsConnectionFactory,
        clientId: String,
        t: Option[Throwable]
      ): ZIO[ZEnv with Logging, Nothing, Unit] = {

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
      ): ZIO[ZEnv with Logging, JMSException, JmsConnection] = {

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
      ): ZIO[ZEnv with Logging, JMSException, JmsConnection] = for {
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
        _  <- addConnection(c)
        _  <- for {
                _ <- log.trace(s"Executing onConnect for [$c]")
                f <- cf.onConnect(c).forkDaemon
                _ <- log.debug(f.toString)
              } yield ()
        _  <- log.debug(s"Created [$c]")
      } yield c

      private[jms] def close(c: JmsConnection): ZIO[ZEnv with Logging, JMSException, Unit] = (ZIO.uninterruptible {
        effectBlocking(c.conn.close) *> removeConnection(c)
      } <* log.debug(s"Closed [$c]")).refineOrDie { case t: JMSException => t }

      private[jms] def shutdown: ZIO[ZEnv with Logging, Nothing, Unit]                     = (for {
        lc <- conns.get.map(_.view.values)
        _  <- ZIO.foreach(lc)(c => close(c))
      } yield ()).catchAll(_ => ZIO.unit)

      // Get a connection from the connection map if it exists
      private def getConnection(id: String): ZIO[ZEnv with Logging, Nothing, Option[JmsConnection]] = for {
        cm <- conns.get
        _  <- log.trace(s"Getting connection [$id]from map : $cm")
        cr  = cm.get(id)
      } yield cr

      private def addConnection(c: JmsConnection): ZIO[ZEnv with Logging, Nothing, Unit] = for {
        cm <- conns.updateAndGet(_ ++ Map(c.id -> c))
        _  <- log.trace(s"Stored JMS connections : $cm")
      } yield ()

      private def removeConnection(c: JmsConnection): ZIO[ZEnv with Logging, Nothing, Unit] = for {
        cm <- conns.updateAndGet(_ - c.id)
        _  <- log.trace(s"Stored JMS connections after deleting [$c]: $cm")
      } yield ()

      // Check whether a connection is currently recovering
      private def isRecovering(id: String): ZIO[Any, Nothing, Boolean] = recovering.get.map(_.contains(id))
    }
  }
}
