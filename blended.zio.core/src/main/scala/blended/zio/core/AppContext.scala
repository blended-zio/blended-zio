package blended.zio.core

import zio._
import java.util.UUID

object AppContext {

  type AppContextService = Has[Service]

  trait Service {
    def appId: String
  }

  def make: ZManaged[Any, Nothing, AppContext.Service] = make(UUID.randomUUID().toString())

  def make(id: String): ZManaged[Any, Nothing, AppContext.Service] = {

    val svc = new DefaultAppContext(id)

    ZManaged.makeEffectTotal(new Service {
      override def appId: String = svc.id
    })(_ => ())
  }
}

sealed private class DefaultAppContext(
  val id: String
)
