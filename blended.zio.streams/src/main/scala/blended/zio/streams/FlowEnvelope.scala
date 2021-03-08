package blended.zio.streams

import scala.language.implicitConversions

import zio._
import scala.reflect.ClassTag

/*
 * The Metadata of a FlowEnvelope represents within the envelope that is required to process the
 * envelope or to maintain the context of the envelope. For example, an envelope created by a JMS
 * receiver might start off with the message properties within the Metadata. An envelope created
 * from a file reader might have a file descriptor within its metadata. Throughout the processing
 * of the envelope, other Metadata elements may be created or filtered out.
 *
 * Some Metadata elements might be required when the envelope content is pushed to an outbound
 * interface - i.e. to add message properties, set HTTP Headers  ....
 *
 * Metadata can also be used to transport context information - i.e. a FlowEnvelope is created
 * from a JMS consumer, is processed by some pipeline and finally we have to decide whether to
 * acknowledge the JMS message or not. This could be achieved, if the JMS consumer also creates
 * an AckHandler and puts it into the Metadata of the envelope.
 *
 * ==> Metatdata must be composable
 * ==> We must be able to find out if a certain piece of Metadata is present in the envelope
 * ==> Has some similarity to Has[_]
 *
 *
 */

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

  implicit def int2Prop(i: Integer) = IntMsgProperty(i)

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

final case class EnvelopeHeader(
  entries: Map[String, MsgProperty[_]] = Map.empty
)

object EnvelopeHeader {

  def apply(hs: (String, MsgProperty[_])*): EnvelopeHeader = EnvelopeHeader(hs.toMap)

  implicit class EnvelopeHeaderSyntax(header: EnvelopeHeader) {

    def add(that: EnvelopeHeader) = EnvelopeHeader(header.entries ++ that.entries)

    /**
     * Remove the headers with the given names
     */
    def removeHeader(name: String*) = EnvelopeHeader(header.entries -- name)

    // TODO: Can we do better here ?
    def get[T: ClassTag](name: String): Option[T] =
      header.entries.get(name) match {
        case Some(p) =>
          p.value match {
            case v: T => Some(v)
            case _    => None
          }
        case _       => None
      }
  }
}

final case class FlowEnvelopeMeta(
  entries: Map[String, Any] = Map.empty
)

object FlowEnvelopeMeta {

  implicit class FlowEnvelopeMetaSyntax(meta: FlowEnvelopeMeta) {
    def add(k: String, v: Any)                  = FlowEnvelopeMeta(meta.entries + (k -> v))
    def get[T: ClassTag](k: String): Option[T]  =
      meta.entries.get(k) match {
        case Some(m: T) => Some(m)
        case _          => None
      }
    def getOrElse[T: ClassTag](k: String, d: T) = get(k).getOrElse(d)
  }
}

final case class FlowEnvelope[C](
  meta: FlowEnvelopeMeta,
  content: C
)
object FlowEnvelope {

  val headerMeta = "header"

  /**
   * Create a default envelope with given content of type C and no headers
   */
  def make[C](c: C) = FlowEnvelope(FlowEnvelopeMeta(), c)

  /**
   * Run an effect to produce some content and then create an envelope from the result
   */
  def fromEffect[R, E, C](e: ZIO[R, E, C]) = e.map(make)

  implicit class FlowEnvelopeSyntax[C](env: FlowEnvelope[C]) {

    /**
     * Simply map the content of the FlowEnvelope by mapping the envelope content and keep the Metadata
     */
    def map[C1](f: C => C1) = FlowEnvelope[C1](env.meta, f(env.content))

    /**
     * Zip two envelopes by building a 2-tuple over the contents add the headers of the right hand side to
     * the headers on the left hand side - potentially overwriting existing headers
     */
    def zip[C1](that: FlowEnvelope[C1]) =
      FlowEnvelope(
        FlowEnvelopeMeta(env.meta.entries ++ that.meta.entries).add(headerMeta, env.header.add(that.header)),
        (env.content, that.content)
      )

    def withMeta(k: String, v: Any) = FlowEnvelope(
      env.meta.add(k, v),
      env.content
    )

    val header = env.meta.getOrElse[EnvelopeHeader](headerMeta, EnvelopeHeader())

    /**
     * Add a named header to the envelope
     */
    def addHeader(newHeader: EnvelopeHeader) =
      FlowEnvelope(
        env.meta.add(headerMeta, header.add(newHeader)),
        env.content
      )

    /**
     * Remove the headers with the given names
     */
    def removeHeader(name: String*): FlowEnvelope[C] =
      FlowEnvelope(
        env.meta.add(headerMeta, header.removeHeader(name: _*)),
        env.content
      )

    /**
     * Replace the current set of envelope headers with a new one
     */
    def setHeader(newHeader: EnvelopeHeader) =
      FlowEnvelope(
        env.meta.add(headerMeta, newHeader),
        env.content
      )
  }
}
