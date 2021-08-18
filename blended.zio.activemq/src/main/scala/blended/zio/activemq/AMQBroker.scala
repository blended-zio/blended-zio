package blended.zio.activemq

import zio._
import zio.blocking._
import zio.logging._

import org.apache.activemq.broker.BrokerService

object AMQBroker {

  type AMQBrokerService = Has[BrokerService]

  def simple(brokerName: String): ZLayer[Logging with Blocking, Throwable, AMQBrokerService] =
    ZLayer.fromManaged(managedBroker(brokerName))

  private def managedBroker(brokerName: String): ZManaged[Logging with Blocking, Throwable, BrokerService] =
    ZManaged.make(startBroker(brokerName))(bs => stopBroker(bs))

  private def startBroker(name: String): ZIO[Logging with Blocking, Throwable, BrokerService] = for {
    _ <- log.info(s"Starting simple ActiveMQ Broker [$name]")
    b <- effectBlockingInterrupt {
           val bs = new BrokerService()
           bs.setUseJmx(true)
           bs.setPersistent(false)
           bs.setUseShutdownHook(true)
           bs.setBrokerName(name)
           bs.start()
           bs.waitUntilStarted()
           bs
         }
  } yield b

  private def stopBroker(bs: BrokerService): ZIO[Logging with Blocking, Nothing, Unit] = for {
    _ <- log.info(s"Stopping simple ActiveMQ Broker [${bs.getBrokerName()}]")
    _ <- effectBlockingInterrupt(bs.stop()).ignore
  } yield ()
}
