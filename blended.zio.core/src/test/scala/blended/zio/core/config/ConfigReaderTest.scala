package blended.zio.core.config

import zio._
import zio.config._
import zio.duration._
import zio.logging._
import zio.logging.slf4j.Slf4jLogger
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

import blended.zio.core.crypto._
import blended.zio.core.evaluator._

object ConfigReaderTest extends DefaultRunnableSpec {

  private val cfgMap: Map[String, String] = Map(
    "url"            -> "ldaps://ldap.$[[env]].$[[country]]:4712",
    "systemUser"     -> "$[(lower)[user]]", // To be replaced from the resolution context
    "systemPassword" -> "$[(encrypted)[5c4e48e1920836f68f1abbaf60e9b026]]", // To be decrypted
    "userBase"       -> "o=employee",
    "userAttribute"  -> "uid",
    "groupBase"      -> "ou=sib,ou=apps,o=global",
    "groupAttribute" -> "cn",
    "groupSearch"    -> "(member={0})"
  )

  // doctag<layer>
  private val logSlf4j = Slf4jLogger.make((_, message) => message)

  private val cryptoDefault =
    CryptoSupport.default.orDie

  private val mods: ZIO[Any, Nothing, Seq[Modifier]] = EncryptedModifier.create.provideLayer(cryptoDefault).map { em =>
    Seq(UpperModifier, LowerModifier, em)
  }

  private val evalLayer: ZLayer[Any, Nothing, StringEvaluator.StringEvaluator] =
    logSlf4j >>> StringEvaluator.fromMods(mods)
  // end:doctag<layer>

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("The Config Reader should")(
    simpleEval(ConfigSource.fromMap(cfgMap)),
    docConfig(ConfigSource.fromMap(cfgMap))
  ) @@ timed @@ timeoutWarning(1.minute) @@ parallel

  // doctag<access>
  private val desc: ConfigDescriptor[LDAPConfig] = LDAPConfig.desc
  private val ctxt: Map[String, String]          = Map("user" -> "ADMIN", "env" -> "dev", "country" -> "es")

  private def simpleEval(src: ConfigSource) = testM("Evaluate a simple config map")(
    (for {
      cfg <- ZIO.fromEither(read(desc.from(src)))
      pwd <- cfg.systemPassword.evaluate(ctxt)
    } yield assert(pwd.value)(equalTo("blended"))).provideLayer(evalLayer)
  )
  // end:doctag<access>

  private def docConfig(src: ConfigSource) = testM("Generate documentation for the config descriptor")(
    (for {
      cfg <- ZIO.fromEither(read(desc.from(src)))
      doc  = generateDocs(desc).toTable
      rep  = generateReport(desc, cfg)
      _   <- log.info(s"\n$doc")
      _   <- log.info(rep.toString())
    } yield assert(true)(isTrue)).provideLayer(logSlf4j)
  )
}

// doctag<config>
object LDAPConfig {

  import LazyConfigString._

  def desc: ConfigDescriptor[LDAPConfig] = (
    configString("url") ?? "The url to connect to the LDAP server" |@|
      configString("systemUser") |@|
      configString("systemPassword") |@|
      configString("userBase") |@|
      configString("userAttribute") |@|
      configString("groupBase") |@|
      configString("groupAttribute") |@|
      configString("groupSearch")
  )(LDAPConfig.apply, LDAPConfig.unapply)
}

case class LDAPConfig(
  url: LazyConfigString.Raw,
  systemUser: LazyConfigString.Raw,
  systemPassword: LazyConfigString.Raw,
  userBase: LazyConfigString.Raw,
  userAttribute: LazyConfigString.Raw,
  groupBase: LazyConfigString.Raw,
  groupAttribute: LazyConfigString.Raw,
  groupSearch: LazyConfigString.Raw
)
// end:doctag<config>
