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

  private val queues = Chunk("sample", "Q/de/9999/data/in")

  private val program = for {
    _ <- ZIO.foreach(queues)(qn => solMgmt.createQueue("default", qn))
    _ <- solMgmt.disableUser("default", "default")
    _ <- solMgmt.createUsername("default", "sib", "sib123")
    _ <- solMgmt.createSubScription("default", "Q/de/9999/data/in", "T/de/9999/data/in")
    _ <- solMgmt.queueSubscriptions("default", "Q/de/9999/data/in")
    _ <- solMgmt.createJNDIConnectionFactory("default", "/SIBConnectionFactory")
    _ <- solMgmt.jndiContext.use { ctxt: NamingContext =>
           for {
             cf <- JNDISupport.lookup[ConnectionFactory](ctxt, "/SIBConnectionFactory")
             _  <- putStrLn(s"Lookup of cf successful [$cf]")
           } yield ()
         }
  } yield ExitCode.success

  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    program.provideCustomLayer(env).orDie
}
