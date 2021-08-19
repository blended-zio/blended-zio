package blended.zio.core

import zio._
import zio.stm._

object RuntimeId {

  trait RuntimeIdSvc {
    def nextId(category: String): ZIO[Any, Nothing, String]
  }

  val default: ZLayer[Any, Nothing, Has[RuntimeIdSvc]] =
    (TMap
      .make[String, Long]()
      .commit
      .map(ids => new DefaultRuntimeIdService(ids))
      .map { impl =>
        new RuntimeIdSvc {
          override def nextId(category: String): ZIO[Any, Nothing, String] = impl.nextId(category)
        }
      })
      .toLayer

  private class DefaultRuntimeIdService(ids: TMap[String, Long]) extends RuntimeIdSvc {

    def nextId(id: String): ZIO[Any, Nothing, String] = (for {
      nextId <- ids.getOrElse(id, 0L).map(_ + 1)
      res     = s"$nextId"
      _      <- ids.put(id, nextId)
    } yield res).commit
  }
}
