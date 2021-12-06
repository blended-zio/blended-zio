package blended.zio.solace

import java.net.URLEncoder
import javax.naming.{ Context => NamingContext }

import zio._

import blended.zio.core.jndi.JNDISupport
import blended.zio.core.json.JsonSupport
import blended.zio.solace.SolaceManagement.SolaceMgmtConnection

import argonaut.Argonaut._
import argonaut._
import com.solacesystems.jms.SupportedProperty
import com.solacesystems.jndi.SolJNDIInitialContextFactory
import sttp.client3._

class SolaceManagement(conn: SolaceMgmtConnection) {

  def clientUsernames(vpn: String) = getKeys(userNameOp(vpn), "clientUsername")
  def queues(vpn: String)          = getKeys(queuesOp(vpn), "queueName")
  def cfJndiNames(vpn: String)     = getKeys(cfJndiOp(vpn), "connectionFactoryName")

  def queueSubscriptions(vpn: String, queue: String) =
    getKeys(s"${queuesOp(vpn)}/${encode(queue)}/subscriptions", "subscriptionTopic")

  def createSubscription(vpn: String, queue: String, subscription: String) = for {
    _       <- ZIO.logInfo(s"Creating Solace Queue Subscription [$vpn,$queue,$subscription]")
    subJson <- ZIO.effect(
                 jObjectFields(
                   ("msgVpnName", jString(vpn)),
                   ("queueName", jString(queue)),
                   ("subscriptionTopic", jString(subscription))
                 )
               )

    res <- ZIO.ifZIO(queueSubscriptions(vpn, queue).map(_.contains(subscription)))(
             ZIO.succeed(false),
             performPost(s"${queuesOp(vpn)}/${encode(queue)}/subscriptions", subJson).map(_ => true)
           )
  } yield res

  def disableUser(vpn: String, userName: String) = for {
    _        <- log.info(s"Disabling Solace User [$vpn,$userName]")
    userJson <- ZIO.effect(
                  jObjectFields(
                    ("clientUsername", jString(userName)),
                    ("enabled", jBool(false))
                  )
                )
    res      <- ZIO.ifM(clientUsernames(vpn).map(_.contains(userName)))(
                  performPut(s"msgVpns/$vpn/clientUsernames/$userName", userJson).map(_ => true),
                  ZIO.succeed(false)
                )
  } yield res

  def createSolaceQueue(vpn: String, sq: SolaceManagement.SolaceQueue) = for {
    q      <- createQueue(vpn, sq.name)
    s      <- ZIO.foreach(sq.subscriptions)(sub => createSubscription(vpn, sq.name, sub))
    changed = s.fold(false)(_ || _)
  } yield q || changed

  def createQueue(vpn: String, queueName: String) = for {
    _         <- log.info(s"Creating Solace Queue [$vpn,$queueName]")
    queueJson <- ZIO.effect(
                   jObjectFields(
                     ("accessType", jString("non-exclusive")),
                     ("egressEnabled", jBool(true)),
                     ("ingressEnabled", jBool(true)),
                     ("msgVpnName", jString(vpn)),
                     ("permission", jString("consume")),
                     ("queueName", jString(queueName))
                   )
                 )
    res       <- ZIO.ifM(queues(vpn).map(_.contains(queueName)))(
                   ZIO.succeed(false),
                   performPost(queuesOp(vpn), queueJson).map(_ => true)
                 )
  } yield res

  def createUsername(vpn: String, userName: String, password: String) = for {
    _        <- log.info(s"Creating Solace User [$vpn,$userName]")
    userJson <- ZIO.effect(
                  jObjectFields(
                    ("clientUsername", jString(userName)),
                    ("enabled", jBool(true)),
                    ("password", jString(password))
                  )
                )
    res      <- ZIO.ifM(clientUsernames(vpn).map(_.contains(userName)))(
                  ZIO.succeed(false),
                  performPost(userNameOp(vpn), userJson)
                )
  } yield res

  def createJNDIConnectionFactory(vpn: String, name: String) = for {
    _      <- log.info(s"Binding Solace Connection Factory [$vpn,$name]")
    cfJson <- ZIO.effect(
                jObjectFields(
                  ("connectionFactoryName", jString(name)),
                  ("msgVpnName", jString(vpn)),
                  ("transportDirectTransportEnabled", jBool(false))
                )
              )
    res    <- ZIO.ifM(cfJndiNames(vpn).map(_.contains(name)))(
                ZIO.succeed(false),
                performPost(cfJndiOp(vpn), cfJson)
              )
  } yield res

  def jndiContext(
    url: String,
    user: String,
    password: String,
    vpn: String
  ): ZManaged[Logging, Throwable, NamingContext] =
    JNDISupport.create(
      Map(
        NamingContext.INITIAL_CONTEXT_FACTORY              -> classOf[SolJNDIInitialContextFactory].getName(),
        NamingContext.PROVIDER_URL                         -> url,
        NamingContext.SECURITY_PRINCIPAL                   -> s"$user@$vpn",
        NamingContext.SECURITY_CREDENTIALS                 -> password,
        SupportedProperty.SOLACE_JMS_AUTHENTICATION_SCHEME -> SupportedProperty.AUTHENTICATION_SCHEME_BASIC
      )
    )

  private def getKeys(operation: String, key: String) = for {
    keys <- performGet(operation)
    res  <- decodeKeys(keys, key)
  } yield res

  private def performGet(operation: String) = retry(for {
    url      <- sempUrl(operation)
    _        <- log.info(s"Performing GET from [$url]")
    response <- effectBlocking {
                  basicRequest.get(uri"$url").auth.basic(conn.user, conn.password).send(backend)
                }
    res      <- response.body match {
                  case Right(s) => ZIO.effect(s) <* log.info(s"GET from [$url] succeeded.") <* log.debug(s)
                  case _        =>
                    for {
                      msg <- ZIO.effect(s"Get from [$url] failed with Status code [${response.code}]")
                      _   <- log.warn(msg)
                      _   <- ZIO.fail(new Exception(msg))
                    } yield ""
                }
  } yield res)

  private def performPut(operation: String, json: Json) = retry(for {
    url      <- sempUrl(operation)
    _        <- log.info(s"Executing put to [$url]")
    response <- effectBlocking {
                  basicRequest
                    .put(uri"$url")
                    .auth
                    .basic(conn.user, conn.password)
                    .body(json.toString())
                    .contentType("application/json")
                    .send(backend)
                }
    res      <- response.body match {
                  case Right(s) => ZIO.effect(s) <* log.info(s"Put to [$url] succeeded")
                  case Left(s)  =>
                    for {
                      msg <- ZIO.effect(s"Operation [$operation] failed with Status code [${response.code}] : $s")
                      _   <- log.warn(msg)
                      _   <- ZIO.fail(new Exception(msg))
                    } yield ""
                }
  } yield res)

  private def performPost(operation: String, json: Json) = retry(for {
    url      <- sempUrl(operation)
    _        <- log.info(s"Executing post to [$url]")
    response <- effectBlocking {
                  basicRequest
                    .post(uri"$url")
                    .auth
                    .basic(conn.user, conn.password)
                    .body(json.toString())
                    .contentType("application/json")
                    .send(backend)
                }
    res      <- response.body match {
                  case Right(s) => log.info(s"Post to [$url] succeeded") <* ZIO.succeed(s)
                  case Left(s)  =>
                    for {
                      msg <- ZIO.effect(s"Post to [$url] failed with Status code [${response.code}] : $s")
                      _   <- log.warn(msg)
                      _   <- ZIO.fail(new Exception(msg))
                    } yield ""
                }
  } yield res)

  // Utility method to decode a list of identifying keys from a SEMP v2
  def decodeKeys(s: String, key: String) = for {
    json <- ZIO.fromEither(Parse.parse(s)).mapError(s => new Exception(s))
    data <- JsonSupport.extract(json, "data").map(_.arrayOrEmpty)
    keys  = data.map(_.field(key).map(_.stringOrEmpty).getOrElse("")).filter(s => !s.isEmpty())
  } yield Chunk.fromIterable(keys)

  private val sempUrl: String => ZIO[Any, Nothing, String] = op => ZIO.succeed(s"${conn.sempv2Base}/$op")

  private val userNameOp: String => String = vpn => s"msgVpns/${encode(vpn)}/clientUsernames"
  private val queuesOp: String => String   = vpn => s"msgVpns/${encode(vpn)}/queues"
  private val cfJndiOp: String => String   = vpn => s"msgVpns/${encode(vpn)}/jndiConnectionFactories"
  private val encode: String => String     = s => URLEncoder.encode(s, "UTF-8")

  private lazy val backend = HttpURLConnectionBackend()

  private def retry[R, E, A](effect: ZIO[R, E, A]) = effect.retry(
    Schedule.recurs(5) && Schedule.spaced(1.second)
  )
}

object SolaceManagement {

  final case class SolaceQueue(
    name: String,
    subscriptions: List[String] = List.empty
  )

  final case class SolaceMgmtConnection(
    url: String,
    user: String,
    password: String
  ) {
    val sempv2Base = s"$url/SEMP/v2/config"
  }
}
