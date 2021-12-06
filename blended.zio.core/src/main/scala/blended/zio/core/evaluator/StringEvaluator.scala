package blended.zio.core.evaluator

import zio._

trait StringEvaluator:
  def resolveString(input: String, context: Map[String, String]): ZIO[Any, EvaluatorException, String]
end StringEvaluator

object StringEvaluator {

  def fromMods(mods: Chunk[Modifier]) = (new DefaultStringEvaluator(mods){})

  private[StringEvaluator] sealed abstract class DefaultStringEvaluator (mods: Chunk[Modifier]) {

    def resolveString(input: String, context: Map[String, String]): ZIO[Any, EvaluatorException, String] = (for {
      expr <- StringExpression.build(input)
      prep <- resolveDelayed(expr)
      res  <- evaluate(prep, context, input)
    } yield (res)).mapError {
      case ee: EvaluatorException => ee
      case t: Throwable           => new InvalidFormatException(input, t.getMessage())
    }

    // We replace ModifierExpression that are marked with the modifier 'delayed' with SimpleExpressions containing
    // the string representation of the inner expression of the delayed ModifierExpression, so that we simply can
    // evaluate the resulting expression without caring about `delayed` evaluation anymore.
    private def resolveDelayed(expr: StringExpression): ZIO[Any, Throwable, StringExpression] = expr match {
      case se: SimpleExpression       => ZIO.succeed(se)
      case me: ModifierExpression     =>
        for {
          mods <- ZIO.succeed(me.modStrings)
          res  = if (mods.contains("delayed")) {
                    StringExpression.asString(me.inner).map { s =>
                      if (mods.size == 1)
                        SimpleExpression(s)
                      else
                        throw new InvalidFormatException(
                          s,
                          "The 'delayed' modifier cannot be combined with other modifiers"
                        )
                    }} else me
        } yield (res)
      case SequencedExpression(parts) =>
        ZIO.collectPar(parts)(p => resolveDelayed(p).mapError(t => Some(t))).map(s => SequencedExpression(s))
    }

    private def evaluate(
      expr: StringExpression,
      context: Map[String, String],
      orig: String
    ): ZIO[Any, Throwable, String] =
      expr match {
        case se: SimpleExpression       => ZIO.succeed(se.value)
        case me: ModifierExpression     => evaluateModifier(me, context, orig)
        case SequencedExpression(parts) =>
          ZIO.collectPar(parts)(p => evaluate(p, context, orig).mapError(t => Some(t))).map(_.mkString)
      }

    private def evaluateModifier(
      me: ModifierExpression,
      context: Map[String, String],
      orig: String
    ): ZIO[Any, Throwable, String] =
      for {
        inner    <- evaluate(me.inner, context, orig)
        modifier <- ZIO.collectPar(me.modStrings)(m => mapSingleMod(m, orig).mapError(t => Some(t)))
        lookup    = modifier.headOption.map(_._1.lookup).getOrElse(true)
        value    <- ZIO.ifM(ZIO.succeed(lookup))(
                      ZIO
                        .fromOption(context.get(inner))
                        .mapError(_ => new UnresolvedVariableException(orig, inner)),
                      ZIO.succeed(inner)
                    )
        res      <- ZIO.foldLeft(modifier)(value) { case (cur, (m, p)) => m.op(cur, p) }
      } yield (res)

    // Extract a single modifier with it's parameters and map it back to one of the evaluators configured
    // Modifiers. If an unknown modifier is referenced, throw an UnknownModifierException
    private def mapSingleMod(modString: String, orig: String): ZIO[Any, Throwable, (Modifier, String)] = for {
      parts   <- ZIO.succeed(modString.split(":").toList)
      mod     <- ZIO.effect {
                  mods.find(_.name.equals(parts.head)) match {
                    case None    => throw new UnknownModifierException(orig, parts.head)
                    case Some(m) => (m, parts.tail.mkString)
                  }
                }
    } yield mod
  }
}