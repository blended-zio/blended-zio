package blended.zio.itest

import zio._
import zio.console._

import blended.zio.core.jndi.JNDISupport
import blended.zio.solace.SolaceManagement

import javax.naming.{ Context => NamingContext }
import javax.jms.ConnectionFactory

object SolaceMgmtDemo extends App {

  private val env = ZEnv.live

  private val solMgmt = new SolaceManagement(
    SolaceManagement.SolaceMgmtConnection("http://devel.wayofquality.de:8080", "admin", "admin")
  )

  private val program = for {
    _ <- solMgmt.disableUser("default", "default")
    _ <- solMgmt.createUsername("default", "sib", "sib123")
    _ <-
      solMgmt.createSolaceQueue(
        "default",
        SolaceManagement.SolaceQueue("Q/de/9999/data/in", List("T/de/9999/data/in", "T/de/9999"))
      )
    _ <- solMgmt.createJNDIConnectionFactory("default", "/SIBConnectionFactory")
    _ <-
      solMgmt.jndiContext("tcp://devel.wayofquality.de:55555", "sib", "sib123", "default").use { ctxt: NamingContext =>
        for {
          cf <- JNDISupport.lookup[ConnectionFactory](ctxt, "/SIBConnectionFactory")
          _  <- putStrLn(s"Lookup of cf successful [$cf]")
        } yield ()
      }
  } yield ExitCode.success

  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    program.provideCustomLayer(env).orDie
}
