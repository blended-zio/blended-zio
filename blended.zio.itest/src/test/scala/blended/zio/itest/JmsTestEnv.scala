package blended.zio.itest

import zio._
import zio.clock._
import zio.blocking._
import zio.logging._

import blended.zio.activemq.AMQBroker

import blended.zio.streams.jms.JmsApi
import blended.zio.streams.jms.JmsApi._

import blended.zio.streams.jms.JmsConnectionManager
import blended.zio.streams.jms.JmsConnectionManager._

import blended.zio.core.RuntimeId
import org.apache.activemq.broker.BrokerService

object JmsTestEnv {

  private val logEnv: ZLayer[ZEnv, Nothing, Logging] = Logging.console(
    logLevel = LogLevel.Trace,
    format = LogFormat.ColoredLogFormat()
  )

  private val brokerEnv: ZLayer[ZEnv, Throwable, Has[BrokerService]] =
    (ZLayer.requires[Blocking] ++ logEnv) >>> AMQBroker.simple("simple")

  private val jmsApiEnv: ZLayer[ZEnv, Nothing, Has[JmsApiSvc]] =
    (logEnv ++ RuntimeId.default) >>> JmsApi.default

  private val jmsConnMgr: ZLayer[ZEnv, Nothing, Has[JmsConnectionManagerSvc]] =
    (Clock.live ++ logEnv ++ jmsApiEnv) >>> JmsConnectionManager.live

  // format: off
  val withoutBroker: ZLayer[
    ZEnv, Nothing, 
    Logging
    with Has[JmsApiSvc]
    with Has[JmsConnectionManagerSvc]
  ] = (ZLayer.requires[ZEnv] ++ Clock.live) >>> (logEnv ++ jmsApiEnv ++ jmsConnMgr)

  val withBroker: ZLayer[
    ZEnv, Nothing, 
    Logging 
    with Has[JmsApiSvc] 
    with Has[BrokerService] 
    with Has[JmsConnectionManagerSvc]
  ] =
  // format: on
  ((ZLayer.requires[ZEnv] ++ Clock.live) >>> (logEnv ++ brokerEnv ++ jmsApiEnv ++ jmsConnMgr)).orDie
}
