package blended.zio.core.evaluator

import java.util.regex.Pattern

import zio._

// doctag<expression>
sealed trait StringExpression
case class SimpleExpression(value: String)                                      extends StringExpression
case class SequencedExpression(parts: Seq[StringExpression])                    extends StringExpression
case class ModifierExpression(modStrings: Seq[String], inner: StringExpression) extends StringExpression
// end:doctag<expression>

object StringExpression {

  private val modPattern: Pattern   = Pattern.compile("\\([^\\)]*\\)")
  private val startPattern: Pattern = Pattern.compile("\\$\\[([^\\[]*)\\[")
  private val endDelim: String      = "]]"

  def build(input: String): ZIO[Any, Throwable, StringExpression] = for {
    (e, r) <- parse(input, 0, None, input)
    parsed <- if (r.isEmpty())
                ZIO.succeed(e)
              else
                ZIO.fail(new InvalidFormatException(input, s"unexpected remainder after parsing [$r]"))
    res    <- simplify(SequencedExpression(flatten(parsed)))
    _      <- ZIO.logDebug(s"Parsed ($input) to ($res)")
  } yield res

  def asString(expr: StringExpression): ZIO[Any, Nothing, String] = expr match {
    case se: SimpleExpression       => ZIO.succeed(se.value)
    case me: ModifierExpression     =>
      for {
        mods  <- ZIO.collectPar(me.modStrings)(m => ZIO.succeed(s"($m)")).map(_.reverse.mkString)
        inner <- asString(me.inner)
      } yield s"$$[$mods[$inner]]"
    case SequencedExpression(parts) => (ZIO.collectPar(parts)(p => asString(p))).map(_.mkString)
  }

  private[evaluator] def flatten(expr: StringExpression): Seq[StringExpression] = expr match {
    case simple: SimpleExpression   => Seq(simple)
    case mod: ModifierExpression    => Seq(ModifierExpression(mod.modStrings, SequencedExpression(flatten(mod.inner))))
    case SequencedExpression(parts) => parts.map(p => flatten(p)).flatten
  }

  private[evaluator] def simplify(expr: StringExpression): ZIO[Any, Nothing, StringExpression] = expr match {
    case simple: SimpleExpression   => ZIO.succeed(simple)
    case mod: ModifierExpression    => simplify(mod.inner).map(m => ModifierExpression(mod.modStrings, m))
    case SequencedExpression(parts) =>
      parts match {
        case Seq()  => ZIO.succeed(SimpleExpression(""))
        case Seq(e) => ZIO.succeed(e)
        case es     =>
          ZIO
            .collectPar(es)(e => simplify(e))
            .map(_.filter(e => !e.equals(SimpleExpression(""))))
            .map(s => SequencedExpression(s))
      }
  }

  private[evaluator] def parse(
    remaining: String,
    level: Int,
    modString: Option[String],
    orig: String
  ): ZIO[Any, Throwable, (StringExpression, String)] = for {
    _              <- ZIO.logDebug(s"Parsing ($remaining), level ($level), modString ($modString), orig ($orig)")
    endIdx          = if (level == 0) remaining.length else remaining.indexOf(endDelim)
    startM         <- ZIO.effect { val m = startPattern.matcher(remaining); m }
    hasSub          = startM.find() && startM.start() < endIdx
    startIdx        = if (hasSub) startM.start() else -1
    (expr, rest)   <- ZIO.ifM(ZIO.succeed(!hasSub))(
                        parseNoSub(remaining, level, orig),
                        parseSub(remaining, startIdx, startM.group(), startM.group(1), level + 1, orig)
                      )
    _              <- ZIO.logDebug(s"Resolved sub expression to ($expr), rest ($rest) level ($level), hasSub ($hasSub)")
    (rp, unparsed) <- ZIO.ifM(ZIO.succeed(rest.isEmpty || !hasSub))(
                        ZIO.succeed((None, rest)),
                        parse(rest, level, None, orig).map { case (e, s) => (Some(e), s) }
                      )
    result          = expr match {
                        case SequencedExpression(parts) => SequencedExpression(parts ++ rp.toSeq)
                        case other                      =>
                          rp match {
                            case None    => other
                            case Some(e) => SequencedExpression(Seq(other, e))
                          }
                      }
  } yield (result, unparsed)

  private def parseSub(
    remaining: String,
    startIdx: Int,
    startGroup: String,
    modString: String,
    level: Int,
    orig: String
  ): ZIO[Any, Throwable, (StringExpression, String)] = for {
    prefix <- ZIO.succeed(if (startIdx == 0) None else Some(remaining.substring(0, startIdx)))
    rem     = remaining.substring(startIdx + startGroup.length)
    mods   <- parseMods(modString, Seq.empty, orig)
    _      <- ZIO.logDebug(s"Parsing sub remainder ($rem) for sub expression level ($level) with modifiers ($mods)")
    (e, r) <- parse(rem, level, Some(modString), orig)
    result  = if (e.equals(SimpleExpression(""))) SimpleExpression("") else ModifierExpression(mods, e)
    _      <- ZIO.logDebug(s"Resolved parsed sub expression ($remaining) to ($result) rest ($r) level ($level)")
  } yield (
    (
      prefix match {
        case None    => result
        case Some(s) => SequencedExpression(Seq(SimpleExpression(s), result))
      },
      r
    )
  )

  private def parseNoSub(
    remaining: String,
    level: Int,
    orig: String
  ): ZIO[Any, Throwable, (StringExpression, String)] =
    if (level == 0) {
      for {
        r <- ZIO.succeed((SimpleExpression(remaining), ""))
      } yield r
    } else
      for {
        endIdx <- ZIO.succeed(remaining.indexOf(endDelim))
        res    <- if (endIdx == -1) {
                    ZIO.fail(
                      new InvalidFormatException(
                        orig,
                        s"Expected delimiter `]]` for [$orig], remaining [$remaining] level ($level)"
                      )
                    )
                  } else
                    for {
                      _    <- ZIO.logDebug(s"Parsing nosub remainder ($remaining), level ($level)")
                      expr <- ZIO.effect(SimpleExpression(remaining.substring(0, endIdx)))
                    } yield ((expr, remaining.substring(endIdx + endDelim.length)))
      } yield res

  // Parse all modifiers for a given expression as a sequence with one String representing each modifier
  private def parseMods(modString: String, current: Seq[String], orig: String): ZIO[Any, Throwable, Seq[String]] =
    if (modString.isEmpty())
      ZIO.succeed(current)
    else
      for {
        matcher <- ZIO.effect(modPattern.matcher(modString))
        nextMod <- ZIO.effect {
                     if (matcher.find()) {
                       if (matcher.start() == 0) {
                         matcher.group()
                       } else {
                         throw new InvalidFormatException(
                           orig,
                           s"Expected modifier expression starting with `(`, got $modString"
                         )
                       }
                     } else {
                       throw new InvalidFormatException(orig, s"Unable to parse modifiers from [$modString]")
                     }
                   }
        allMods <- if (nextMod.equals(modString)) {
                     ZIO.succeed(Seq(nextMod.drop(1).dropRight(1)) ++ current)
                   } else {
                     parseMods(modString.substring(nextMod.length), Seq(nextMod.drop(1).dropRight(1)) ++ current, orig)
                   }
      } yield allMods
}
