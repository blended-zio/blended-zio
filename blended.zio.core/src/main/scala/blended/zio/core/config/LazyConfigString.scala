package blended.zio.core.config

import zio._
import zio.config._, ConfigDescriptor._

import blended.zio.core.evaluator._

// doctag<descriptor>
sealed abstract case class LazyConfigString(value: String)

object LazyConfigString {

  import ConfigDescriptorAdt._

  final case class Raw(raw: String) {
    def evaluate(
      ctxt: Map[String, String]
    ): ZIO[StringEvaluator.StringEvaluator, EvaluatorException, LazyConfigString] = for {
      se  <- ZIO.service[StringEvaluator.Service]
      res <- se.resolveString(raw, ctxt)
    } yield (new LazyConfigString(res) {})
  }

  val configString: ConfigDescriptor[Raw]               =
    Source(ConfigSource.empty, LazyConfigStringType) ?? "lazyly evaluated config string"
  def configString(path: String): ConfigDescriptor[Raw] = nested(path)(configString)

  private case object LazyConfigStringType extends PropertyType[String, LazyConfigString.Raw] {
    def read(v: String): Either[PropertyType.PropertyReadError[String], LazyConfigString.Raw] = Right(Raw(v))
    def write(a: LazyConfigString.Raw): String                                                = a.raw
  }
}
// end:doctag<descriptor>
