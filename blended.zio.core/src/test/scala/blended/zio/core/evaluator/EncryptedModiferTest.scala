package blended.zio.core.evaluator

import zio._
import zio.duration._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

import blended.zio.core.crypto.CryptoSupport

object EncryptedModifierTest extends DefaultRunnableSpec {

  private val cryptoDefault: ZLayer[Any, Nothing, CryptoSupport.CryptoSupport] =
    CryptoSupport.default.orDie

  private val encMod: ZIO[Any, Nothing, Modifier] = EncryptedModifier.create.provideLayer(cryptoDefault)

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] =
    (suite("The encryption  modifier should")(
      testM("Simply decrypt a given String")(checkM(for {
        s <- Gen.stringBounded(1, 100)(Gen.anyASCIIChar)
      } yield s) { value =>
        for {
          cs  <- ZIO.service[CryptoSupport.Service]
          dec <- cs.encrypt(value)
          mod <- encMod
          res <- mod.op(dec, "")
        } yield assert(res)(equalTo(value))
      }),
      testM("fail if called with a null value")(for {
        m <- encMod
        r <- m.modifier(null, "").run
      } yield assert(r)(fails(assertion("expect")() {
        case me: ModifierException =>
          me.segment.equals("") && me.param.equals("") && me.msg.equals("Segment can't be null")
        case _                     => false
      })))
    ).provideCustomLayer(cryptoDefault)) @@ timed @@ timeoutWarning(1.minute) @@ parallel
}
