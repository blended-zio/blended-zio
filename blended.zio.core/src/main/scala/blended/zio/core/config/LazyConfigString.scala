package blended.zio.core.config

import zio._
import zio.config._

import blended.zio.core.evaluator._

import ConfigDescriptor._

// doctag<descriptor>
sealed abstract case class LazyConfigString(value: String)

object LazyConfigString {

  final case class Raw(raw: String) {
    def evaluate(
      ctxt: Map[String, String]
    ): ZIO[StringEvaluator.StringEvaluator, EvaluatorException, LazyConfigString] = for {
      se  <- ZIO.service[StringEvaluator.Service]
      res <- se.resolveString(raw, ctxt)
    } yield (new LazyConfigString(res) {})
  }

  val configString: ConfigDescriptor[Raw]               =
    string(Raw.apply, Raw.unapply)
  def configString(path: String): ConfigDescriptor[Raw] = nested(path)(configString)

}
// end:doctag<descriptor>
