package blended.zio.core.evaluator

import zio._
import zio.logging._

object StringEvaluator {

  type StringEvaluator = Has[Service]

  trait Service {
    def resolveString(input: String, context: Map[String, String]): ZIO[Any, EvaluatorException, String]
  }

  def fromMods(mods: ZIO[Any, Nothing, Seq[Modifier]]): ZLayer[Logging, Nothing, StringEvaluator] =
    ZLayer.fromFunction { log =>
      val svc = new DefaultStringEvaluator(mods)

      new Service {
        override def resolveString(input: String, context: Map[String, String]): ZIO[Any, EvaluatorException, String] =
          svc.resolveString(input, context).provide(log)
      }
    }
}

final private class DefaultStringEvaluator(mods: ZIO[Any, Nothing, Seq[Modifier]]) {

  private val resolvedModsRef: Ref[Option[Seq[Modifier]]] =
    Runtime.default.unsafeRun(Ref.make[Option[Seq[Modifier]]](None))

  private def resolvedMods: ZIO[Any, Nothing, Seq[Modifier]] = for {
    maybeRef <- resolvedModsRef.get
    res      <- ZIO.fromOption(maybeRef).orElse {
                  for {
                    modSeq <- mods
                    _      <- resolvedModsRef.set(Some(modSeq))
                  } yield modSeq
                }
  } yield (res)

  def resolveString(input: String, context: Map[String, String]): ZIO[Logging, EvaluatorException, String] = (for {
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
  private def resolveDelayed(expr: StringExpression): ZIO[Logging, Throwable, StringExpression] = expr match {
    case se: SimpleExpression       => ZIO.succeed(se)
    case me: ModifierExpression     =>
      for {
        mods <- ZIO.succeed(me.modStrings)
        res  <- ZIO.ifM(ZIO.succeed(mods.contains("delayed")))(
                  StringExpression.asString(me.inner).map { s =>
                    if (mods.size == 1)
                      SimpleExpression(s)
                    else
                      throw new InvalidFormatException(
                        s,
                        "The 'delayed' modifier cannot be combined with other modifiers"
                      )
                  },
                  ZIO.succeed(me)
                )
      } yield (res)
    case SequencedExpression(parts) =>
      ZIO.collectPar(parts)(p => resolveDelayed(p).mapError(t => Some(t))).map(s => SequencedExpression(s))
  }

  private def evaluate(
    expr: StringExpression,
    context: Map[String, String],
    orig: String
  ): ZIO[Logging, Throwable, String] =
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
  ): ZIO[Logging, Throwable, String] =
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
    resMods <- resolvedMods
    mod     <- ZIO.effect {
                 resMods.find(_.name.equals(parts.head)) match {
                   case None    => throw new UnknownModifierException(orig, parts.head)
                   case Some(m) => (m, parts.tail.mkString)
                 }
               }
  } yield mod
}
