package blended.zio.core.crypto

import zio._
import zio.duration._
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect._

object CryptoTest extends DefaultRunnableSpec {

  private val cryptoDefault: ZLayer[Any, Nothing, CryptoSupport.CryptoSupport] =
    CryptoSupport.default.orDie

  private val cryptoPwd: ZLayer[Any, Nothing, CryptoSupport.CryptoSupport] =
    CryptoSupport.fromPassword("MyCoolPassword").orDie

  private val cryptoNoFile: ZLayer[Any, Nothing, CryptoSupport.CryptoSupport] =
    CryptoSupport.fromFile("/tmp/completelyRandom").orDie

  override def spec: ZSpec[_root_.zio.test.environment.TestEnvironment, Any] = suite("The Crypto support should")(
    cryptoRoundtrip.provideCustomLayer(cryptoDefault),
    cryptoRoundtrip.provideCustomLayer(cryptoPwd),
    cryptoRoundtrip.provideCustomLayer(cryptoNoFile)
  ) @@ timed @@ timeoutWarning(1.minute) @@ parallel

  private val cryptoRoundtrip = testM("encrypt and decrypt arbitrary Strings")(
    checkM(for {
      s <- Gen.stringBounded(10, 100)(Gen.alphaNumericChar)
    } yield s) { value =>
      for {
        cs  <- ZIO.service[CryptoSupport.Service]
        enc <- cs.encrypt(value)
        dec <- cs.decrypt(enc)
      } yield (
        assert(dec)(equalTo(value))
      )
    }
  )
}
