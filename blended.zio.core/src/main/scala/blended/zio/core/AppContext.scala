package blended.zio.core

import java.util.UUID

trait AppContext:
  def appId : String
end AppContext


object AppContext:
  def apply() : AppContext = apply(UUID.randomUUID().toString)
  def apply(id: String) : AppContext = new AppContext {
    override def appId: String = id
  }
end AppContext
