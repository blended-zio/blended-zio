package blended.zio.streams

import scala.language.implicitConversions

import izumi.reflect.Tag
import zio._

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
 * an AckHandler and outs it into the Metadata of the envelope.
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

  implicit def bool2Prop(b: Boolean) = BooleanMsgProperty(b)
  implicit def byte2Prop(b: Byte) = ByteMsgProperty(b)
  implicit def short2Prop(s: Short) = ShortMsgProperty(s)
  implicit def char2Prop(c : Char) = CharMsgProperty(c)
  implicit def int2Prop(i: Int) = IntMsgProperty(i)
  implicit def long2Prop(l: Long) = LongMsgProperty(l)
  implicit def float2Prop(f: Float) = FloatMsgProperty(f)
  implicit def dbl2Prop(d: Double) = DoubleMsgProperty(d)
  implicit def string2Prop(s: String) = StringMsgProperty(s)

  implicit def int2Prop(i : Integer) = IntMsgProperty(i)

  def make(v : Any) : Option[MsgProperty[_]] = v match {
    case b : Boolean => Some(b)
    case b : Byte => Some(b)
    case s : Short => Some(s)
    case c : Char => Some(c)
    case i : Int => Some(i)
    case l : Long => Some(l)
    case f : Float => Some(f)
    case d : Double => Some(d)
    case s : String => Some(s)
    case _ => None
  }
}

/*
 * A flow envelope that has metadata of type M and content of type C.
 *
 * We want all envelopes to support EnvelopeHeaders, which are basically a Map[String, MsgProperty[_]].
 * When we first create an envelope, we will populate the headers with an empty map, so that we are sure
 * to always have envelope headers.
 *
 */
final case class FlowEnvelope[M <: Has[Map[String, MsgProperty[_]]], C](
  meta: M,
  content: C
) {

  import FlowEnvelope.EnvelopeHeader

  /**
   * Simply map the content of the FlowEnvelope by mapping the envelope content and keep the Metadata
   */
  def map[C1](f: C => C1) = FlowEnvelope(meta, f(content))

  /**
   * Zip two envelopes by building a 2-tuple over the contents add the headers of the right hand side to
   * the headers on the left hand side - potentially overwriting existing headers
   */
  def zip[M1 <: Has[EnvelopeHeader], C1](env: FlowEnvelope[M1, C1]) =
    FlowEnvelope(meta.update[EnvelopeHeader](left => left ++ env.meta.get[EnvelopeHeader]), (content, env.content))

  /**
   * Stick an arbitrary object into the Metadata, so that it can be extracted with a proper type later on.
   */
  def withMeta[M2: Tag](m: M2): FlowEnvelope[M with Has[M2], C] = FlowEnvelope(meta.add[M2](m), content)

  /**
    * Add a named header to the envelope
    */
  def addHeader( header : (String, MsgProperty[_])*) : FlowEnvelope[M, C] = 
    FlowEnvelope(
      meta.update[EnvelopeHeader](_ ++ header.toMap), content
    )

  /**
    * Remove the headers with the given names 
    */  
  def removeHeader(name : String*) : FlowEnvelope[M, C] = 
    FlowEnvelope(
      meta.update[EnvelopeHeader](_ -- name), content
    )

  /**
    * Replace the current set of envelope headers with a new one
    */  
  def setHeader(header : (String, MsgProperty[_])*) : FlowEnvelope[M, C] = 
    FlowEnvelope(meta.update[EnvelopeHeader](_ => header.toMap), content)

  // TODO: Can we do better here ?  
  def header[T : Tag](name: String)(implicit tag: Tag[T]) : Option[T] = {

    val typeMatches : Map[String, Seq[String]] = Map(
      "java.lang.Integer" -> Seq("int")
    )


    meta.get[EnvelopeHeader].get(name) match {
      case Some(p) => 
        val stored = p.value.getClass().getName()
        val desired = tag.closestClass.getName()
        if (stored.equals(desired) || typeMatches.get(stored).map(_.contains(desired)).getOrElse(false)) Some(p.value.asInstanceOf[T]) else None 
      case _ => None
    }
  }
  
}

object FlowEnvelope {
  type EnvelopeHeader = Map[String, MsgProperty[_]]

  /**
    * Create a default envelope with given content of type C and no headers 
    */
  def make[C](c: C) = FlowEnvelope(Has(noHeader), c)

  /**
    * Run an effect to produce some content and then create an envelope from the result 
    */
  def fromEffect[R, E, C](e : ZIO[R, E, C]) = e.map(make)

  private[streams] val noHeader: EnvelopeHeader = Map[String, MsgProperty[_]]()
}

object Foo {

  import FlowEnvelope.EnvelopeHeader

  val env  = FlowEnvelope.make("Hello Andreas")
  val env2 = env.map[Int](_.size)
  val env3 = env.zip(env).map { case (c, s) => (c, s.size) }
  val env4 = env.map(_ => ()).withMeta(4.3f).withMeta(4)
  val env5 = env.withMeta(4)

  val xx: Int                            = env5.meta.get[Int]
  val props: Map[String, MsgProperty[_]] = env5.meta.get[Map[String, MsgProperty[_]]]

  def blabla[M <: (Has[EnvelopeHeader] with Has[Float] with Has[Int]): Tag, C](env: FlowEnvelope[M, C]) = env.content

  val foo = blabla(env4)

}
