package blended.zio.jolokia

import zio._
import zio.blocking._
import zio.logging._

import argonaut._
import sttp.client3._

import scala.util._

import JolokiaObject._
import java.net.URLEncoder

object JolokiaClient {

  final case class JolokiaAddress(
    jolokiaUrl: String = "http://127.0.0.1:7777/jolokia",
    user: Option[String] = None,
    password: Option[String] = None
  )

  class JolokiaClient(address: JolokiaAddress) {

    val version = for {
      json <- performGet("version")
      v    <- JolokiaVersion.fromJson(json)
    } yield v

    def search(searchDef: MBeanSearchDef) = for {
      op   <- ZIO.effectTotal(searchDef.operation)
      json <- performGet(op)
      res  <- JolokiaSearchResult.fromJson(json)
    } yield res

    def read(name: String) = for {
      op   <- ZIO.effectTotal(s"read/${URLEncoder.encode(name, "UTF-8")}")
      json <- performGet(op)
      res  <- JolokiaReadResult.fromJson(name, json)
    } yield res

    def exec(execDef: OperationExecDef) = for {
      op   <- ZIO.effectTotal(s"exec/${execDef.pattern}")
      json <- performGet(op)
      res  <- JolokiaExecResult.fromJson(json)
    } yield res

    private def performGet(operation: String) = for {
      _    <- log.debug(s"performing Jolokia operation [$operation]")
      uri  <- jolokiaUrl(operation)
      req   = (address.user, address.password) match {
                case (Some(u), Some(p)) => basicRequest.get(uri"$uri").auth.basic(u, p)
                case (_, _)             => basicRequest.get(uri"$uri").header("X-Blended", "jolokia")
              }
      res  <- effectBlocking(req.send(backend))
      body <- res.body match {
                case Right(s) => log.info(s"Jolokia call to [$uri] succeeded") *> ZIO.succeed(s)
                case Left(s)  =>
                  for {
                    msg <- ZIO.effect(s"Post to [$uri] failed with Status code [${res.code}] : $s")
                    _   <- log.warn(msg)
                    _   <- ZIO.fail(new Exception(msg))
                  } yield ""
              }
      json <- ZIO.fromEither(Parse.parse(body)).mapError(s => new Exception(s))
    } yield json

    private val jolokiaUrl: String => ZIO[Any, Nothing, String] = op => ZIO.succeed(s"${address.jolokiaUrl}/$op")
    private lazy val backend                                    = HttpURLConnectionBackend()

  }
}
