package blended.zio.streams

import zio.duration._

import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect._

object FlowEnvelopeTest extends DefaultRunnableSpec {

  override def spec = suite("A FlowEnvelope should")(
    simpleEnvelope,
    canMap,
    meta,
    simpleHeader,
    zip
  ) @@ timed @@ timeout(3.seconds) @@ parallel

  private val simpleEnvelope = test("create an envelope from a given value with an empty set of headers") {
    val env = FlowEnvelope.make("Hello Andreas")
    assert(env.meta.get[FlowEnvelope.EnvelopeHeader])(isEmpty)
  }

  private val canMap = test("allows to map the content") {
    val s   = "Hello Andreas"
    val env = FlowEnvelope.make(s).map(_.size)
    assert(env.content)(equalTo(s.size))
  }

  private val meta = test("Allows to stick an arbitrary object into the envelopes metadata") {
    val s    = "Hello Andreas"
    val mInt = 7
    val env  = FlowEnvelope.make(s).withMeta(mInt)
    assert(env.meta.get[Int])(equalTo(mInt))
  }

  private val simpleHeader = test("allow to set and get a simple header") {
    val s   = "Hello Andreas"
    val env = FlowEnvelope.make(s).addHeader("foo" -> "bar")

    val lookupOk   = env.header[String]("foo")
    val lookupFail = env.header[Int]("foo")

    assert(lookupOk)(equalTo(Some("bar"))) && assert(lookupFail)(isNone)
  }

  private val zip = test("allow to zip 2 envelopes") {
    val s = "Hello Andreas"

    val env1 = FlowEnvelope.make(s).addHeader("foo" -> "bar")
    val env2 = FlowEnvelope.make(7).addHeader("prop" -> 45)

    val env = env1.zip(env2)

    println(env.meta.get[FlowEnvelope.EnvelopeHeader].mkString(","))

    val lookup1 = env.header[String]("foo")
    val lookup2 = env.header[Int]("prop")

    val a1 = assert(env.content)(equalTo((s, 7)))
    val a2 = assert(lookup1)(equalTo(Some("bar")))
    val a3 = assert(lookup2)(equalTo(Some(45)))

    a1 && a2 && a3
  }

}
