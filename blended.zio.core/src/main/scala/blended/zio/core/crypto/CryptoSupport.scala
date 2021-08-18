package blended.zio.core.crypto

import java.io.IOException
import java.security.Key
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

import scala.io.Source

import zio._

abstract class CryptoException(msg: String) extends Exception(msg) {

  def this(cause: Throwable) = {
    this(cause.getMessage())
    initCause(cause)
  }
}

class CryptoFrameworkException(cause: Throwable) extends CryptoException(cause)

class InvalidInputException(s: String)
  extends CryptoException(s"The String ($s) could not be transformed into a byte array for decryption")

object CryptoSupport {

  private val keyBytes: Int = 16
  private val alg: String   = "AES"

  private val defaultPwd: String = "vczP26-QZ5n%$8YP"
  private val salt: Array[Char]  = ("V*YE6FPXW6#!g^hD" + "*" * keyBytes).substring(0, keyBytes).toCharArray()

  // doctag<service>
  trait CryptoSvc {
    def encrypt(plain: String): ZIO[Any, CryptoException, String]
    def decrypt(encrypted: String): ZIO[Any, CryptoException, String]
  }
  // end:doctag<service>

  val default: ZLayer[Any, CryptoException, Has[CryptoSvc]] = fromPassword(defaultPwd)

  def fromPassword(password: String): ZLayer[Any, CryptoException, Has[CryptoSvc]] = ZLayer.fromEffect(
    createWithPassword(password)
  )

  def fromFile(filename: String): ZLayer[Any, CryptoException, Has[CryptoSvc]] = ZLayer.fromEffect(
    password(filename).flatMap(createWithPassword)
  )

  private def createWithPassword(pwd: String): ZIO[Any, CryptoException, CryptoSvc] =
    saltedPassword(pwd).flatMap(key).flatMap(createService)

  private def createService(k: Key): ZIO[Any, Nothing, CryptoSvc] =
    ZIO.succeed(new DefaultCryptoSupport(k, alg)).map { cs =>
      new CryptoSvc {
        override def encrypt(plain: String): ZIO[Any, CryptoException, String]     =
          cs.encrypt(plain)
        override def decrypt(encrypted: String): ZIO[Any, CryptoException, String] =
          cs.decrypt(encrypted)
      }
    }

  private def keySource(filename: String): ZManaged[Any, IOException, Source] = Managed.make {
    ZIO.effect(Source.fromFile(filename)).refineOrDie { case ioe: IOException => ioe }
  } { source =>
    ZIO.effect(source.close()).catchAll(_ => ZIO.succeed(())) // do nothing
  }

  private def password(filename: String): ZIO[Any, Nothing, String] =
    keySource(filename)
      .use(source => ZIO.effect(source.getLines()))
      .map(_.nextOption())
      .map(_.getOrElse(defaultPwd))
      .catchAll(_ => ZIO.succeed(defaultPwd))

  private def saltedPassword(password: String): ZIO[Any, Nothing, String] =
    ZIO.succeed(password.zip(salt).map { case (a, b) => a.toString() + b.toString }.mkString)

  private def key(salted: String): ZIO[Any, CryptoException, Key]         =
    ZIO
      .effect(new SecretKeySpec(salted.substring(0, keyBytes).getBytes("UTF-8"), alg))
      .mapError(t => new CryptoFrameworkException(t))

}

// doctag<crypto>
final private class DefaultCryptoSupport(key: Key, alg: String) {

  def decrypt(encrypted: String): ZIO[Any, CryptoException, String] = for {
    bytes     <- string2Byte(encrypted)
    ciph      <- cipher(Cipher.DECRYPT_MODE)
    decrypted <- ZIO.effect(ciph.doFinal(bytes.toArray)).refineOrDie { case t => new CryptoFrameworkException(t) }
  } yield (new String(decrypted))

  def encrypt(plain: String): ZIO[Any, CryptoException, String] = for {
    ciph  <- cipher(Cipher.ENCRYPT_MODE)
    bytes <- ZIO.effect(ciph.doFinal(plain.getBytes())).refineOrDie { case t => new CryptoFrameworkException(t) }
    res   <- byte2String(bytes.toSeq)
  } yield res

  private def cipher(mode: Int): ZIO[Any, CryptoException, Cipher] =
    ZIO.effect { val res = Cipher.getInstance(alg); res.init(mode, key); res }.refineOrDie { case t =>
      new CryptoFrameworkException(t)
    }

  private def byte2String(a: Seq[Byte]): ZIO[Any, Nothing, String] =
    ZIO.collectPar(a)(b => ZIO.succeed(Integer.toHexString(b & 0xff | 0x100).substring(1))).map(_.mkString)

  private def string2Byte(s: String, orig: Option[String] = None): ZIO[Any, CryptoException, Seq[Byte]] = {
    val radix: Int = 16

    (s match {
      case ""                               => ZIO.succeed(Seq.empty)
      case single if (single.length() == 1) =>
        ZIO.effect(Seq(Integer.parseInt(single, radix).toByte)).refineOrDie { case _: NumberFormatException =>
          new InvalidInputException(orig.getOrElse(s))
        }
      case s                                =>
        string2Byte(s.substring(2), Some(orig.getOrElse(s)))
          .map(rest => Seq(Integer.parseInt(s.substring(0, 2), radix).toByte) ++ rest)
    })
  }
}
// end:doctag<crypto>
