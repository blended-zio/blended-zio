package blended.zio.itest.container

import scala.language.implicitConversions

import zio._

import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.Network

import blended.zio.itest.condition.LDAPAvailableCondition.LDAPConnectionData

class ApacheDS extends GenericContainer("blended/apacheds-alpine:1.0.1", exposedPorts = Seq(10389))

object ApacheDS {

  def apply() = new ApacheDS()

  implicit def toTestcontainer(a: ApacheDS) = new TestContainer[ApacheDS] {
    override val container = {
      a.configure { c => c.withNetwork(Network.SHARED); () }
      a.configure { c => c.withNetworkAliases("apacheds"); () }
      a
    }
  }

  val ldapConnection = for {
    ct <- ZIO.service[ApacheDS]
    p   = ct.mappedPort(10389)
  } yield LDAPConnectionData(s"ldap://localhost:${p}", "uid=admin,ou=system", "blended")
}
