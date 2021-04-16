package blended.zio.itest.container

import scala.language.implicitConversions
import java.time.Duration

import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy
import org.testcontainers.containers.Network

class Splunk
  extends GenericContainer(
    dockerImage = "blended/splunk:8.1.1.2",
    exposedPorts = Seq(Splunk.uiPort, Splunk.reportPort),
    waitStrategy = Some(new HostPortWaitStrategy().withStartupTimeout(Duration.ofMinutes(2)))
  )

object Splunk {

  val uiPort     = 8000
  val reportPort = 8088

  def apply() = new Splunk()

  implicit def toTestcontainer(a: Splunk) = new TestContainer[Splunk] {

    override val container = {
      a.configure { c => c.withNetwork(Network.SHARED); () }
      a.configure { c => c.withNetworkAliases("splunk"); () }
      a
    }
  }
}
