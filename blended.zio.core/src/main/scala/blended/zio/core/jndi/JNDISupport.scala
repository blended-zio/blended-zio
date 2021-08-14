package blended.zio.core.jndi

import java.util.Hashtable
import javax.naming._

import scala.reflect.ClassTag

import zio._
import zio.logging._

object JNDISupport {

  def create(env: Map[String, String]): ZManaged[Logging, NamingException, Context] = ZManaged.make((ZIO.effect {
    val htEnv = new Hashtable[Any, Any]()
    env.foreach { case (k, v) => htEnv.put(k, v) }
    new InitialContext(htEnv)
  } <* log.info(s"Created Initial Context Factory with ${env.mkString("\n", "\n", "")}")).refineOrDie {
    case ne: NamingException => ne
  })(ctxt => ZIO.effect(ctxt.close()).ignore)

  def bind(ctxt: Context, name: String, ref: Any): ZIO[Logging, NamingException, Unit] = for {
    _ <- log.info(s"Binding [$ref] to [$name]")
    _ <- ZIO.effect {
           ctxt.bind(name, ref)
         }.refineOrDie { case ne: NamingException => ne }
  } yield ()

  def rebind(ctxt: Context, name: String, ref: Any): ZIO[Logging, NamingException, Unit] =
    bind(ctxt, name, ref).catchAll { _ =>
      ZIO.effect(ctxt.rebind(name, ref)).refineOrDie { case ne: NamingException => ne }
    }

  def lookup[T: ClassTag](ctxt: Context, name: String): ZIO[Logging, Nothing, Option[T]] = (for {
    obj <- ZIO.effect(ctxt.lookup(name))
    res  = obj match {
             case v: T => Some(v)
             case _    => None
           }
    _   <- log.info(s"Lookup of [$name] returned [$res]")
  } yield res).catchAll { t =>
    // TODO: print out stacktrace to log
    ZIO.effectTotal(t.printStackTrace()) *> ZIO.none
  }
}
