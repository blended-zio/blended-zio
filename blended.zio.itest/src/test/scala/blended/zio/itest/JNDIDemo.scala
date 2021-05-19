package blended.zio.itest

import javax.jms.ConnectionFactory
import javax.naming.Context

import zio._
import zio.console._
import zio.logging._

import blended.zio.core.jndi.JNDISupport

import com.solacesystems.jndi.SolJNDIInitialContextFactory

object JNDIDemo extends App {
  val logLayer = Logging.console(
    logLevel = LogLevel.Debug,
    format = LogFormat.ColoredLogFormat()
  ) >>> Logging.withRootLoggerName(getClass().getSimpleName())

  override def run(args: List[String]): URIO[ZEnv, ExitCode] = (for {
    _ <- putStrLn("Looking up connection factory")
    _ <- JNDISupport
           .create(
             Map(
               Context.INITIAL_CONTEXT_FACTORY -> classOf[SolJNDIInitialContextFactory].getName(),
               Context.PROVIDER_URL            -> "smf://localhost:55555",
               Context.SECURITY_PRINCIPAL      -> "sib",
               Context.SECURITY_CREDENTIALS    -> "sib123"
             )
           )
           .use { ctxt =>
             for {
               _ <- JNDISupport.lookup[ConnectionFactory](ctxt, "/SIBConnectionFactory")
             } yield ()
           }
           .catchAll(t => ZIO.effectTotal(t.printStackTrace()))
  } yield ExitCode.success).catchAll(_ => ZIO.succeed(ExitCode.failure)).provideCustomLayer(logLayer)
}
