package blended.zio.core

import zio._
import zio.stm._

object RuntimeId {

  trait RuntimeIdSvc {
    def nextId(category: String): ZIO[Any, Nothing, String]
  }

  def default: ZLayer[Any, Nothing, Has[RuntimeIdSvc]] = ZLayer.fromEffect(makeService)

  private val makeService = for {
    idMap <- TMap.make[String, Long]().commit
  } yield new DefaultRuntimeIdService(idMap)

  private class DefaultRuntimeIdService(ids: TMap[String, Long]) extends RuntimeIdSvc {

    def nextId(id: String): ZIO[Any, Nothing, String] = (for {
      nextId <- ids.getOrElse(id, 0L).map(_ + 1)
      res     = s"$nextId"
      _      <- ids.put(id, nextId)
    } yield res).commit
  }
}
