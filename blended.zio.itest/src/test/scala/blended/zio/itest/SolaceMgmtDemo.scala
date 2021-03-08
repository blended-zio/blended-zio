package blended.zio.itest

import zio._
import zio.console._
import zio.blocking._
import sttp.client3._

import argonaut._
import Argonaut._

class SolaceManagement(conn: SolaceManagement.SolaceMgmtConnection) {

  def clientUsernames(vpn: String) = getKeys(s"msgVpns/$vpn/clientUsernames", "clientUsername")

  def queues(vpn: String) = getKeys(s"msgVpns/$vpn/queues", "queueName")

  def createQueue(vpn: String, queueName: String) = {
    val queueJson: Json = jObjectFields(
      ("accessType", jString("non-exclusive")),
      ("msgVpnName", jString(vpn)),
      ("permission", jString("consume")),
      ("queueName", jString(queueName))
    )

    ZIO.ifM(queues(vpn).map(_.contains(queueName)))(
      ZIO.succeed(false),
      performPut(s"msgVpns/$vpn/queues", queueJson).map(_ => true)
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
    url      <- ZIO.succeed(s"${conn.sempv2Base}/$operation")
    response <- effectBlocking {
                  basicRequest.get(uri"$url").auth.basic(conn.user, conn.password).send(backend)
                }
    res       = response.body match {
                  case Right(s) => s
                  case _        => throw new Exception(s"Operation [$operation] failed with Status code [${response.code}]")
                }
  } yield res

  private def performPut(operation: String, json: Json) = for {
    url      <- ZIO.succeed(s"${conn.sempv2Base}/$operation")
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

  private val queues = Chunk("sample", "Qude/9999/data/in")

  private val program = for {
    values <- ZIO.foreach(queues)(qn => solMgmt.createQueue("default", qn))
    _      <- putStrLn(values.toString)
  } yield ExitCode.success

  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    program.provideCustomLayer(env).orDie
}
