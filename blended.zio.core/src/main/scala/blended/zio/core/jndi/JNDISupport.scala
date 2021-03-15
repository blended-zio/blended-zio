package blended.zio.core.jndi

import javax.naming._

import zio._
import java.util.Hashtable
import scala.reflect.ClassTag

object JNDISupport {

  def create(env: Map[String, String]): ZManaged[Any, NamingException, Context]      = ZManaged.make((ZIO.effect {
    val htEnv = new Hashtable[Any, Any]()
    env.foreach { case (k, v) => htEnv.put(k, v) }
    new InitialContext(htEnv)
  }).refineOrDie { case ne: NamingException => ne })(ctxt => ZIO.effect(ctxt.close()).orDie)

  def bind(ctxt: Context, name: String, ref: Any): ZIO[Any, NamingException, Unit]   = ZIO.effect {
    ctxt.bind(name, ref)
  }.refineOrDie { case ne: NamingException => ne }

  def rebind(ctxt: Context, name: String, ref: Any): ZIO[Any, NamingException, Unit] =
    bind(ctxt, name, ref).catchAll { _ =>
      ZIO.effect(ctxt.rebind(name, ref)).refineOrDie { case ne: NamingException => ne }
    }

  def lookup[T: ClassTag](ctxt: Context, name: String): ZIO[Any, Nothing, Option[T]] = (for {
    obj <- ZIO.effect(ctxt.lookup(name))
    res  = obj match {
             case v: T => Some(v)
             case _    => None
           }
  } yield res).catchAll { case _ => ZIO.none }

}
