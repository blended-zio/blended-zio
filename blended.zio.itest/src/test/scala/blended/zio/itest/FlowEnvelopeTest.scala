package blended.zio.streams

import zio._
import zio.duration._
import zio.logging.slf4j._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

import blended.zio.itest.EnvelopeAssertion._
import blended.zio.streams.MsgProperty._
import blended.zio.streams._

object FlowEnvelopeTest extends DefaultRunnableSpec {

  private val logEnv = Slf4jLogger.make((_, message) => message)

  override def spec = (suite("A FlowEnvelope should")(
    simpleEnvelope,
    canMap,
    simpleHeader,
    zip,
    ack
  ) @@ timed @@ timeout(3.seconds) @@ parallel).provideCustomLayerShared(logEnv)

  private val simpleEnvelope = test("create an envelope from a given value with an empty set of headers") {
    val env = FlowEnvelope.make("Hello Andreas")
    assert(env.header.entries)(isEmpty)
  }

  private val canMap = test("allows to map the content") {
    val s   = "Hello Andreas"
    val env = FlowEnvelope.make(s).mapContent(_.size)
    assert(env.content)(equalTo(s.size))
  }

  private val simpleHeader = test("allow to set and get a simple header") {
    val s          = "Hello Andreas"
    val env        = FlowEnvelope
      .make(s)
      .addHeader(EnvelopeHeader(Map("foo" -> "bar")))

    val lookupOk   = assert(env)(hasHeader("foo", "bar"))
    val lookupFail = assert(env.header.get[Int]("foo"))(
      equalTo(Left(HeaderException.HeaderUnexpectedType("foo", classOf[Int].getName(), classOf[String].getName())))
    )

    val erased = assert(env.removeMeta(EnvelopeHeader.key).header.entries)(isEmpty)

    lookupOk && lookupFail && erased
  }

  private val zip = test("allow to zip some envelopes") {

    val s = "Hello Andreas"

    val env1 = FlowEnvelope.make(s).addHeader(EnvelopeHeader(Map("foo" -> "bar")))
    val env2 = FlowEnvelope.make(7).addHeader(EnvelopeHeader(Map("prop" -> 45)))
    val env3 = FlowEnvelope.make(2.0)

    val env = env1.zip(env2).zip(env3)

    val lookup1 = env.header.get[String]("foo")
    val lookup2 = env.header.get[Int]("prop")

    val a1 = assert(env.content)(equalTo((s, 7, 2.0)))
    val a2 = assert(lookup1)(equalTo(Right("bar")))
    val a3 = assert(lookup2)(equalTo(Right(45)))

    a1 && a2 && a3
  }

  private val ack = testM("should call the AckHandler")(
    for {
      ref   <- Ref.make(false)
      env   <- ZIO.effect(
                 FlowEnvelope
                   .make("Hello Ack")
                   .withMeta[AckHandler](
                     AckHandler.key,
                     new AckHandler {
                       override def ack  = ref.set(true)
                       override def deny = ZIO.unit
                     }
                   )
               )
      _     <- env.ackOrDeny
      acked <- ref.get
    } yield assert(acked)(isTrue)
  )
}
