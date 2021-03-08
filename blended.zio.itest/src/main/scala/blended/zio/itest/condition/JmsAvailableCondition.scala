package blended.zio.itest.condition

import zio._

import blended.zio.streams.jms.JmsApi
import blended.zio.streams.jms.JmsApiObject._
import blended.zio.streams.jms.JmsConnectionManager

object JmsAvailableCondition {

  def checkJms(cf: JmsConnectionFactory, clientId: String) = ZManaged.make {
    JmsApi.connect(cf, clientId)
  } { con =>
    (for {
      mgr <- ZIO.service[JmsConnectionManager.Service]
      _   <- mgr.close(con)
    } yield (())).orDie
  }
}
