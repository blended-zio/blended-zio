package blended.zio.jolokia

import blended.zio.core.json.JsonSupport._

import argonaut._

sealed trait JolokiaObject

object JolokiaObject {

  final case class JolokiaExecResult(
    objectName: String,
    operationName: String,
    value: Json
  ) extends JolokiaObject

  object JolokiaExecResult {
    def fromJson(result: Json) = for {
      objectName <- extract(result, "request", "mbean").map(_.stringOrEmpty)
      operation  <- extract(result, "request", "operation").map(_.stringOrEmpty)
      value      <- extract(result, "value")
    } yield JolokiaExecResult(objectName, operation, value)
  }

  final case class JolokiaReadResult(
    objectName: String,
    attributes: Map[String, Json]
  ) extends JolokiaObject

  object JolokiaReadResult {
    def fromJson(objectName: String, jsValue: Json) = for {
      attrs <- attributes(jsValue)
    } yield JolokiaReadResult(objectName, attrs)
  }

  final case class JolokiaSearchResult(mbeanNames: List[String]) extends JolokiaObject

  object JolokiaSearchResult {
    def fromJson(jsValue: Json) = for {
      ex   <- extract(jsValue, "value")
      names = ex.arrayOrEmpty.map(_.stringOrEmpty)
    } yield JolokiaSearchResult(names)
  }

  final case class JolokiaVersion(
    agent: String,
    protocol: String,
    config: Map[String, String]
  ) extends JolokiaObject

  object JolokiaVersion {
    def fromJson(json: Json) = for {
      agent    <- extract(json, "value", "agent").map(_.stringOrEmpty)
      protocol <- extract(json, "value", "protocol").map(_.stringOrEmpty)
      assocs   <- extract(json, "value", "config")
      asFields <- attributes(assocs)
      config    = asFields.view.mapValues(_.stringOrEmpty).filter { case (_, v) => !v.isEmpty() }.toMap
    } yield JolokiaVersion(agent, protocol, config)
  }
}
