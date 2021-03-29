package blended.zio.streams

import scala.language.implicitConversions
import scala.reflect.ClassTag

sealed trait MsgProperty[T] {
  def value: T
}

object MsgProperty {

  final case class BooleanMsgProperty(override val value: Boolean) extends MsgProperty[Boolean]
  final case class ByteMsgProperty(override val value: Byte)       extends MsgProperty[Byte]
  final case class ShortMsgProperty(override val value: Short)     extends MsgProperty[Short]
  final case class CharMsgProperty(override val value: Char)       extends MsgProperty[Char]
  final case class IntMsgProperty(override val value: Int)         extends MsgProperty[Int]
  final case class LongMsgProperty(override val value: Long)       extends MsgProperty[Long]
  final case class FloatMsgProperty(override val value: Float)     extends MsgProperty[Float]
  final case class DoubleMsgProperty(override val value: Double)   extends MsgProperty[Double]
  final case class StringMsgProperty(override val value: String)   extends MsgProperty[String]

  implicit def bool2Prop(b: Boolean)  = BooleanMsgProperty(b)
  implicit def byte2Prop(b: Byte)     = ByteMsgProperty(b)
  implicit def short2Prop(s: Short)   = ShortMsgProperty(s)
  implicit def char2Prop(c: Char)     = CharMsgProperty(c)
  implicit def int2Prop(i: Int)       = IntMsgProperty(i)
  implicit def long2Prop(l: Long)     = LongMsgProperty(l)
  implicit def float2Prop(f: Float)   = FloatMsgProperty(f)
  implicit def dbl2Prop(d: Double)    = DoubleMsgProperty(d)
  implicit def string2Prop(s: String) = StringMsgProperty(s)

  def make(v: Any): Option[MsgProperty[_]] = v match {
    case b: Boolean => Some(b)
    case b: Byte    => Some(b)
    case s: Short   => Some(s)
    case c: Char    => Some(c)
    case i: Int     => Some(i)
    case l: Long    => Some(l)
    case f: Float   => Some(f)
    case d: Double  => Some(d)
    case s: String  => Some(s)
    case _          => None
  }
}

sealed trait HeaderException

object HeaderException {
  final case class HeaderNotFound(name: String, expectedClass: String)                           extends HeaderException
  final case class HeaderUnexpectedType(name: String, expectedClass: String, foundClass: String) extends HeaderException
}

final case class EnvelopeHeader(
  entries: Map[String, MsgProperty[_]] = Map.empty
) {
  def ++(that: EnvelopeHeader) = EnvelopeHeader(entries ++ that.entries)
  def --(name: String*)        = EnvelopeHeader(entries -- name)

  def get[T](name: String)(implicit tag: ClassTag[T]): Either[HeaderException, T] =
    entries.get(name) match {
      case Some(p) =>
        p.value match {
          case v: T => Right(v)
          case o    => Left(HeaderException.HeaderUnexpectedType(name, tag.runtimeClass.getName(), o.getClass().getName()))
        }
      case _       => Left(HeaderException.HeaderNotFound(name, tag.runtimeClass.getName()))
    }
}

object EnvelopeHeader {

  val empty = EnvelopeHeader()

  val key: EnvelopeMeta[EnvelopeHeader] = EnvelopeMeta[EnvelopeHeader](
    id = "header",
    init = empty,
    comb = (h1: EnvelopeHeader, h2: EnvelopeHeader) => h1 ++ h2
  )
}
