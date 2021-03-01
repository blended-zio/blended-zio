package blended.zio.core

import zio._
import zio.stm._

object RuntimeId {

  type RuntimeIdService = Has[Service]

  trait Service {
    def nextId(category: String): ZIO[Any, Nothing, String]
  }

  object Service {

    def make: ZLayer[Any, Nothing, RuntimeIdService] = ZLayer.fromEffect(makeService)

    private val makeService = for {
      ids <- TMap.make[String, Long]().commit
    } yield {

      val idSvc = new DefaultRuntimeIdService(ids) {}

      new Service {
        override def nextId(category: String) = idSvc.nextId(category)
      }
    }

    sealed abstract private class DefaultRuntimeIdService(ids: TMap[String, Long]) {

      def nextId(id: String): ZIO[Any, Nothing, String] = (for {
        nextId <- ids.getOrElse(id, 0L).map(_ + 1)
        res     = s"$nextId"
        _      <- ids.put(id, nextId)
      } yield res).commit
    }
  }
}
