package blended.zio.itest.condition

import java.util.Hashtable
import javax.naming.Context
import javax.naming.directory.InitialDirContext

import zio._
import zio.blocking._

object LDAPAvailableCondition {

  case class LDAPConnectionData(
    url: String,
    user: String,
    pwd: String
  ) {

    lazy val env = {
      val r = new Hashtable[String, String]()
      r.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
      r.put(Context.PROVIDER_URL, url)
      r.put(Context.SECURITY_PRINCIPAL, user)
      r.put(Context.SECURITY_CREDENTIALS, pwd)

      r
    }
  }

  def checkLDAP(cd: LDAPConnectionData) =
    dirCtxt(cd).use(c => effectBlocking(c.getNameInNamespace()))

  private def dirCtxt(cd: LDAPConnectionData) =
    ZManaged.make {
      effectBlocking {
        new InitialDirContext(cd.env)
      }.orDie
    } { ctx =>
      effectBlocking {
        ctx.close()
      }.orDie
    }
}
