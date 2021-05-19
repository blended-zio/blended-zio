package blended.zio.core.evaluator

import zio._
import zio.duration._
import zio.logging.slf4j.Slf4jLogger
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object ExpressionTest extends DefaultRunnableSpec {

  private val logSlf4j = Slf4jLogger.make((_, message) => message)

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = (suite("The expression builder should")(
    simpleString,
    simpleExpr,
    modifierExpr,
    seqExpr,
    nestedExpr
  ).provideCustomLayer(logSlf4j)) @@ timed @@ timeoutWarning(1.minute) @@ parallel

  private val simpleString = testM("build from a simple string")(
    for {
      str   <- ZIO.succeed("test")
      expr  <- StringExpression.build(str)
      sExpr <- StringExpression.asString(expr)
    } yield (assert(expr)(equalTo(SimpleExpression("test")))) &&
      assert(sExpr)(equalTo(str))
  )

  private val simpleExpr = testM("build from a simple expression")(
    for {
      str   <- ZIO.succeed("test $[[foo]]")
      expr  <- StringExpression.build(str)
      sExpr <- StringExpression.asString(expr)
    } yield assert(expr)(
      equalTo(
        SequencedExpression(
          Seq(
            SimpleExpression("test "),
            ModifierExpression(Seq.empty, SimpleExpression("foo"))
          )
        )
      )
    ) &&
      assert(sExpr)(equalTo(str))
  )

  private val modifierExpr = testM("build from a simple expression")(
    for {
      str   <- ZIO.succeed("test $[(upper)(left:2)[foo]]")
      expr  <- StringExpression.build(str)
      sExpr <- StringExpression.asString(expr)
    } yield assert(expr)(
      equalTo(
        SequencedExpression(
          Seq(
            SimpleExpression("test "),
            ModifierExpression(Seq("left:2", "upper"), SimpleExpression("foo"))
          )
        )
      )
    ) &&
      assert(sExpr)(equalTo(str))
  )

  private val seqExpr = testM("build from a simple sequence")(
    for {
      str   <- ZIO.succeed("test $[[foo]] $[[bar]]")
      expr  <- StringExpression.build(str)
      sExpr <- StringExpression.asString(expr)
    } yield assert(expr)(
      equalTo(
        SequencedExpression(
          Seq(
            SimpleExpression("test "),
            ModifierExpression(Seq.empty, SimpleExpression("foo")),
            SimpleExpression(" "),
            ModifierExpression(Seq.empty, SimpleExpression("bar"))
          )
        )
      )
    ) &&
      assert(sExpr)(equalTo(str))
  )

  private val nestedExpr = testM("parse nested expressions")(
    for {
      str   <- ZIO.succeed("$[[start]] test $[[foo$[[bar]]]] $[[bar]] testrest")
      expr  <- StringExpression.build(str)
      sExpr <- StringExpression.asString(expr)
    } yield assert(expr)(
      equalTo(
        SequencedExpression(
          Seq(
            ModifierExpression(Seq.empty, SimpleExpression("start")),
            SimpleExpression(" test "),
            ModifierExpression(
              Seq.empty,
              SequencedExpression(Seq(SimpleExpression("foo"), ModifierExpression(Seq.empty, SimpleExpression("bar"))))
            ),
            SimpleExpression(" "),
            ModifierExpression(Seq.empty, SimpleExpression("bar")),
            SimpleExpression(" testrest")
          )
        )
      )
    ) &&
      assert(sExpr)(equalTo(str))
  )
}
