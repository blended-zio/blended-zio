package blended.zio.itest

import java.net.URLEncoder

import zio._
import zio.console._
import zio.blocking._
import sttp.client3._

import argonaut._
import argonaut.Argonaut._

import SolaceManagement.SolaceMgmtConnection

class SolaceManagement(conn: SolaceMgmtConnection) {

  def clientUsernames(vpn: String) = getKeys(userNameOp(vpn), "clientUsername")

  def queues(vpn: String) = getKeys(queuesOp(vpn), "queueName")

  def queueSubscriptions(vpn: String, queue: String) =
    getKeys(s"${queuesOp(vpn)}/${encode(queue)}/subscriptions", "subscriptionTopic")

  def createSubScription(vpn: String, queue: String, subscription: String) = {
    val subJson = jObjectFields(
      ("msgVpnName", jString(vpn)),
      ("queueName", jString(queue)),
      ("subscriptionTopic", jString(subscription))
    )

    ZIO.ifM(queueSubscriptions(vpn, queue).map(_.contains(subscription)))(
      ZIO.succeed(false),
      performPost(s"${queuesOp(vpn)}/${encode(queue)}/subscriptions", subJson).map(_ => true)
    )
  }

  def disableUser(vpn: String, userName: String) = {
    val userJson = jObjectFields(
      ("clientUsername", jString(userName)),
      ("enabled", jBool(false))
    )

    ZIO.ifM(clientUsernames(vpn).map(_.contains(userName)))(
      performPut(s"msgVpns/$vpn/clientUsernames/$userName", userJson).map(_ => true),
      ZIO.succeed(false)
    )
  }

  def createQueue(vpn: String, queueName: String) = {
    val queueJson: Json = jObjectFields(
      ("accessType", jString("non-exclusive")),
      ("msgVpnName", jString(vpn)),
      ("permission", jString("consume")),
      ("queueName", jString(queueName))
    )

    ZIO.ifM(queues(vpn).map(_.contains(queueName)))(
      ZIO.succeed(false),
      performPost(s"msgVpns/$vpn/queues", queueJson).map(_ => true)
    )
  }

  def createUsername(vpn: String, userName: String, password: String) = {
    val userJson = jObjectFields(
      ("clientUsername", jString(userName)),
      ("enabled", jBool(true)),
      ("password", jString(password))
    )

    ZIO.ifM(clientUsernames(vpn).map(_.contains(userName)))(
      ZIO.succeed(false),
      performPost(userNameOp(vpn), userJson)
    )
  }

  private def getKeys(operation: String, key: String): ZIO[Blocking, Throwable, List[String]] = for {
    keys <- performGet(operation)
    res   = decodeKeys(keys, key) match {
              case Left(s)  => throw new Exception(s)
              case Right(v) => v
            }
  } yield res

  private def performGet(operation: String): ZIO[Blocking, Throwable, String] = for {
    url      <- sempUrl(operation)
    response <- effectBlocking {
                  basicRequest.get(uri"$url").auth.basic(conn.user, conn.password).send(backend)
                }
    res       = response.body match {
                  case Right(s) => s
                  case _        => throw new Exception(s"Operation [$operation] failed with Status code [${response.code}]")
                }
  } yield res

  private def performPut(operation: String, json: Json) = for {
    url      <- sempUrl(operation)
    response <- effectBlocking {
                  basicRequest
                    .put(uri"$url")
                    .auth
                    .basic(conn.user, conn.password)
                    .body(json.toString())
                    .contentType("application/json")
                    .send(backend)
                }
    res       = response.body match {
                  case Right(s) => s
                  case Left(s)  =>
                    throw new Exception(s"Operation [$operation] failed with Status code [${response.code}] : $s")
                }
  } yield res

  private def performPost(operation: String, json: Json) = for {
    url      <- sempUrl(operation)
    response <- effectBlocking {
                  basicRequest
                    .post(uri"$url")
                    .auth
                    .basic(conn.user, conn.password)
                    .body(json.toString())
                    .contentType("application/json")
                    .send(backend)
                }
    res       = response.body match {
                  case Right(s) => s
                  case Left(s)  =>
                    throw new Exception(s"Operation [$operation] failed with Status code [${response.code}] : $s")
                }
  } yield res

  private def decodeKeys(s: String, key: String) =
    Parse
      .parse(s)
      .fold(Left(_), json => Right(json.field("data")))
      .fold(
        Left(_),
        data =>
          data match {
            case None    => Left(s"Could not decode list of [$key]")
            case Some(d) => Right(d.arrayOrEmpty)
          }
      )
      .fold(Left(_), entries => Right(entries.map(_.field(key))))
      .fold(Left(_), entries => Right(entries.collect { case Some(s) => s }))
      .fold(Left(_), entries => Right(entries.map(_.stringOrEmpty)))

  private val sempUrl: String => ZIO[Any, Nothing, String] = op => ZIO.succeed(s"${conn.sempv2Base}/$op")

  private val userNameOp: String => String = vpn => s"msgVpns/${encode(vpn)}/clientUsernames"
  private val queuesOp: String => String   = vpn => s"msgVpns/${encode(vpn)}/queues"

  private val encode: String => String = s => URLEncoder.encode(s, "UTF-8")

  private lazy val backend = HttpURLConnectionBackend()

}

object SolaceManagement {

  final case class SolaceMgmtConnection(
    url: String,
    user: String,
    password: String
  ) {
    val sempv2Base = s"$url/SEMP/v2/config"
  }
}

object SolaceMgmtDemo extends App {

  private val env = ZEnv.live

  private val solMgmt = new SolaceManagement(
    SolaceManagement.SolaceMgmtConnection("http://devel.wayofquality.de:8080", "admin", "admin")
  )

  private val queues = Chunk("sample", "Q/de/9999/data/in")

  private val program = for {
    _   <- ZIO.foreach(queues)(qn => solMgmt.createQueue("default", qn))
    _   <- solMgmt.disableUser("default", "default")
    _   <- solMgmt.createUsername("default", "sib", "sib123")
    _   <- solMgmt.createSubScription("default", "Q/de/9999/data/in", "T/de/9999/data/in")
    sub <- solMgmt.queueSubscriptions("default", "Q/de/9999/data/in")
    _   <- putStrLn(sub.toString)
  } yield ExitCode.success

  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    program.provideCustomLayer(env).orDie
}
