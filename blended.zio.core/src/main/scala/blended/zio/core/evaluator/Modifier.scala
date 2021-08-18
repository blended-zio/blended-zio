package blended.zio.core.evaluator

import zio._

import blended.zio.core.crypto.CryptoSupport

// doctag<modifier>
trait Modifier {
  def name: String
  def op(s: String, p: String): ZIO[Any, Throwable, String]

  def lookup: Boolean = true

  final def modifier(
    s: String,
    p: String
  ): ZIO[Any, ModifierException, String] = (for {
    input <- ZIO.fromOption(Option(s))
    param  = Option(p).getOrElse("")
    mod   <- op(input, param)
  } yield mod).mapError {
    case me: ModifierException => me
    case t: Throwable          => new ModifierException(this, s, p, t.getMessage)
    case _                     => new ModifierException(this, "", p, "Segment can't be null")
  }
}
// end:doctag<modifier>

object UpperModifier extends Modifier {
  override def name: String                                          = "upper"
  override def op(s: String, p: String): ZIO[Any, Throwable, String] =
    ZIO.effect(s.toUpperCase)
}

object LowerModifier extends Modifier {
  override def name: String                                          = "lower"
  override def op(s: String, p: String): ZIO[Any, Throwable, String] =
    ZIO.effect(s.toLowerCase)
}

object CapModifier extends Modifier {
  override def name: String                                          = "capitalize"
  override def op(s: String, p: String): ZIO[Any, Throwable, String] =
    ZIO.effect(s.capitalize)
}

object RightModifier extends Modifier {

  override val name: String = "right"

  override def op(s: String, p: String): ZIO[Any, Throwable, String] = for {
    n <- ZIO.effect(p.toInt)
    s <- if (n <= 0)
           ZIO.fail(new ModifierException(this, s, p, "Expected a number > 0"))
         else
           ZIO.succeed(if (n >= s.length) s else s.takeRight(n))
  } yield s
}

object LeftModifier extends Modifier {

  override val name: String = "left"

  override def op(s: String, p: String): ZIO[Any, Throwable, String] = for {
    n <- ZIO.effect(p.toInt)
    s <- if (n <= 0)
           ZIO.fail(new ModifierException(this, s, p, "Expected a number > 0"))
         else
           ZIO.succeed(if (n >= s.length) s else s.take(n))
  } yield s
}

object ReplaceModifier extends Modifier {
  override def name: String = "replace"

  override def op(s: String, p: String): ZIO[Any, Throwable, String] = for {
    params <- ZIO.effect(Option(p).getOrElse("").split(":"))
    s      <- if (params.length != 2)
                ZIO.fail(new ModifierException(this, s, p, s"Parameter should have the form '<from>:<to>'"))
              else
                ZIO.succeed(s.replaceAll(params(0), params(1)))
  } yield s
}

// doctag<decrypt>
object EncryptedModifier {

  def create = for {
    cs <- ZIO.service[CryptoSupport.CryptoSvc]
    mod = new Modifier {
            override def name: String = "encrypted"

            override def lookup = false

            override def op(s: String, p: String): ZIO[Any, Throwable, String] = (for {
              res <- cs.decrypt(s)
            } yield (res))
          }
  } yield mod
}
// end:doctag<decrypt>
