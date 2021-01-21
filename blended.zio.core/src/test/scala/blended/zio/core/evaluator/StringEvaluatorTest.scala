package blended.zio.core.evaluator

import zio._
import zio.logging.slf4j._
import zio.duration._
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect._

object StringEvaluatorTest extends DefaultRunnableSpec {

  private val logSlf4j = Slf4jLogger.make((_, message) => message)

  private val evalLayer: ZLayer[Any, Nothing, StringEvaluator.StringEvaluator] =
    logSlf4j >>> StringEvaluator.fromMods(ZIO.succeed(Seq(UpperModifier, LeftModifier, RightModifier)))

  private val context: Map[String, String] = Map(
    "foo"    -> "bar",
    "bar"    -> "test",
    "nested" -> "blah"
  )

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] =
    suite("The default String evaluator should")(
      noMod,
      simpleReplace,
      undelimited,
      unresolved,
      unresolvedNested,
      nested,
      oneMod,
      unknownMod,
      manyMods,
      resolveAll,
      delayed
    ).provideCustomLayerShared(evalLayer) @@ timed @@ timeoutWarning(1.minute) @@ parallel

  private val noMod = testM("do not modify a string without any modifiers")(checkM(for {
    s <- Gen.stringBounded(5, 100)(Gen.alphaNumericChar)
  } yield s) { value =>
    for {
      se <- ZIO.service[StringEvaluator.Service]
      r  <- se.resolveString(value, context)
    } yield assert(r)(equalTo(value))
  })

  private val simpleReplace = testM("replace the place holder for a property with it's value")(for {
    se <- ZIO.service[StringEvaluator.Service]
    s   = "$[[foo]]"
    r  <- se.resolveString(s, context)
  } yield assert(r)(equalTo("bar")))

  private val undelimited = testM("fail if the end delimiter is missing")(for {
    se <- ZIO.service[StringEvaluator.Service]
    s   = "$[[foo"
    r  <- se.resolveString(s, context).run
  } yield assert(r)(fails(assertion("Unresolved")()(_.isInstanceOf[InvalidFormatException]))))

  private val unresolved = testM("fail if a variable can't be resolved")(for {
    se <- ZIO.service[StringEvaluator.Service]
    s   = "pre$[[blah]]post"
    r  <- se.resolveString(s, context).run
  } yield assert(r)(fails(assertion("Unresolved")() {
    case ur: UnresolvedVariableException => ur.varName.equals("blah") && ur.line.equals(s)
    case _                               => false
  })))

  private val unresolvedNested = testM("fail if a nested variable can't be resolved")(for {
    se <- ZIO.service[StringEvaluator.Service]
    s   = "pre$[[$[[nested]]]]post"
    r  <- se.resolveString(s, context).run
  } yield assert(r)(fails(assertion("Unresolved")() {
    case ur: UnresolvedVariableException => ur.varName.equals("blah") && ur.line.equals(s)
    case _                               => false
  })))

  private val nested = testM("should replace a nested value in the replacement String")(for {
    se <- ZIO.service[StringEvaluator.Service]
    s   = "$[[$[[foo]]]]"
    r  <- se.resolveString(s, context)
  } yield assert(r)(equalTo("test")))

  private val oneMod = testM("should apply a single modifier")(for {
    se <- ZIO.service[StringEvaluator.Service]
    s   = "$[(upper)[foo]]"
    r  <- se.resolveString(s, context)
  } yield assert(r)(equalTo("BAR")))

  private val unknownMod = testM("should fail if an unknown modifier is used")(for {
    se <- ZIO.service[StringEvaluator.Service]
    s   = "$[(dummy)[foo]]"
    r  <- se.resolveString(s, context).run
  } yield assert(r)(fails(assertion("UnknownResolver")() {
    case ure: UnknownModifierException => ure.line.equals(s) && ure.modName.equals("dummy")
    case _                             => false
  })))

  private val manyMods = testM("should apply combined modifiers")(for {
    se <- ZIO.service[StringEvaluator.Service]
    s   = "$[(upper)(left:2)[foo]]"
    r  <- se.resolveString(s, context)
  } yield assert(r)(equalTo("BA")))

  private val resolveAll = testM("should resolve all modifiers within a string")(for {
    se <- ZIO.service[StringEvaluator.Service]
    s   = "$[[foo]] some text $[[bar]]"
    r  <- se.resolveString(s, context)
  } yield assert(r)(equalTo("bar some text test")))

  private val delayed = testM("should delay the resolution for the modifier 'delayed'")(for {
    se <- ZIO.service[StringEvaluator.Service]
    s   = "$[[foo]] $[(delayed)[$[[nested]]]] $[[bar]]"
    r  <- se.resolveString(s, context)
  } yield assert(r)(equalTo("bar $[[nested]] test")))
}
