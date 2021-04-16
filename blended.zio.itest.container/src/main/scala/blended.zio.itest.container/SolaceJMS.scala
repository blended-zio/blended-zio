package blended.zio.itest.container

import zio._
import scala.language.implicitConversions

import java.time.Duration

import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy
import org.testcontainers.containers.Network

class SolaceJMS(adminUser: String, adminPassword: String)
  extends GenericContainer(
    dockerImage = "solace/solace-pubsub-standard:latest",
    exposedPorts = Seq(SolaceJMS.httpPort, SolaceJMS.jmsPort),
    waitStrategy = Some(new HostPortWaitStrategy().withStartupTimeout(Duration.ofMinutes(2))),
    env = Map("username_admin_globalaccesslevel" -> adminUser, "username_admin_password" -> adminPassword)
  )

object SolaceJMS {

  val httpPort = 8080
  val jmsPort  = 55555

  def apply(user: String, pwd: String) = new SolaceJMS(user, pwd)

  implicit def toTestcontainer(a: SolaceJMS) = new TestContainer[SolaceJMS] {
    override val container = {
      a.configure { c => c.withSharedMemorySize(1024 * 1024 * 1024); () }
      a.configure { c => c.withNetwork(Network.SHARED); () }
      a.configure { c => c.withNetworkAliases("solace"); () }
      a
    }
  }

  val solaceAdminUrl = for {
    ctSol <- ZIO.service[SolaceJMS]
    p      = ctSol.mappedPort(httpPort)
  } yield s"http://localhost:$p"

  val solaceJmsUrl = for {
    ctSol <- ZIO.service[SolaceJMS]
    p      = ctSol.mappedPort(jmsPort)
  } yield s"smf://localhost:$p"
}
