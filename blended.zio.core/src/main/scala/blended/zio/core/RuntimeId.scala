package blended.zio.core

import zio._
import zio.stm._

trait RuntimeId:
  def nextId(category: String) : UIO[String]
end RuntimeId

object RuntimeId: 

  val Live = TMap.empty[String, Long].commit.map(DefaultRuntimeId.apply)

  case class DefaultRuntimeId private[RuntimeId] (ids: TMap[String, Long]) extends RuntimeId: 
    
    override def nextId(id: String): ZIO[Any, Nothing, String] = (for {
      nextId <- ids.getOrElse(id, 0L).map(_ + 1)
      res     = s"$nextId"
      _      <- ids.put(id, nextId)
    } yield res).commit

  end DefaultRuntimeId

end RuntimeId
