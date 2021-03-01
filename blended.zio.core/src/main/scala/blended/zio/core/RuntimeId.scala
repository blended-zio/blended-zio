package blended.zio.core

import zio._
import zio.stm._

object RuntimeId {

  type RuntimeIdService = Has[Service]

  trait Service {
    def nextId(category: String): ZIO[Any, Nothing, String]
  }

  def default: ZLayer[Any, Nothing, RuntimeIdService] = ZLayer.fromEffect(makeService)

  private val makeService = for {
    idMap <- TMap.make[String, Long]().commit
  } yield {

    val idSvc = new DefaultRuntimeIdService {
      override private[core] val ids: TMap[String, Long] = idMap
    }

    new Service {
      override def nextId(category: String) = idSvc.nextId(category)
    }
  }

  sealed abstract private class DefaultRuntimeIdService {

    private[core] val ids: TMap[String, Long]

    def nextId(id: String): ZIO[Any, Nothing, String] = (for {
      nextId <- ids.getOrElse(id, 0L).map(_ + 1)
      res     = s"$nextId"
      _      <- ids.put(id, nextId)
    } yield res).commit
  }
}
