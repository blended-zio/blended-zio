package blended.zio.jmx.publish

import java.util.Date
import java.{ lang => jl, math => jm }
import javax.management.openmbean._
import javax.management.{ MBeanAttributeInfo, ObjectName }

import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag

import zio.duration._
import zio.random.Random
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

import blended.zio.jmx.JmxObjectName

object OpenMBeanMapperTest extends DefaultRunnableSpec {

  private val mapper: OpenMBeanMapper = new OpenMBeanMapper()

  private val maxSize: Int = 50

  private val genDigit      = Gen.oneOf(Gen.char('0', '9'))
  private val genLowercase  = Gen.oneOf(Gen.char('a', 'z'))
  private val genUppercase  = Gen.oneOf(Gen.char('A', 'Z'))
  private val genNameChar   = Gen.oneOf(genDigit, genLowercase, genUppercase)
  private val genSomeString = Gen.stringBounded(1, maxSize)(genNameChar)

  private val genSomeBigDecimal = Gen.bigDecimal(BigDecimal(Double.MinValue), BigDecimal(Double.MaxValue))
  private val genSomeBigInt     = Gen.bigInt(BigInt(Long.MinValue), BigInt(Long.MaxValue))

  private val genSomeDate = Gen.long(0, 1000000).map(l => new Date(System.currentTimeMillis() + l))

  private val genJmxObjectName = for {
    domain <- genSomeString
    map    <- Gen.mapOfBounded(1, 5)(genSomeString, genSomeString)
  } yield JmxObjectName(domain, map)

  private val genObjectName = genJmxObjectName.map(n => new ObjectName(n.objectName))

  override def spec: ZSpec[zio.test.environment.TestEnvironment, Any] = suite("The OpenMBeanMapper should")(
    (testPrimitives ++ testArrays ++ testSeqs ++ testMaps ++ testJavaColls ++ testJavaMaps ++ Seq(testCaseClass)): _*
  ) @@ timed @@ timeoutWarning(1.minute) @@ parallel

  private val testPrimitives = {
    def testPrimitive[T](gen: Gen[Random, T], pType: SimpleType[_], box: T => _ = null, testUnboxed: Boolean = true)(
      implicit cTag: ClassTag[T]
    ) =
      testM(s"map type ${cTag.runtimeClass}")(check(gen) { value =>
        val prim  = mapper.fieldToElement("prim", value)
        val boxed = Option(box).map(b => mapper.fieldToElement("boxed", b(value)))

        assert(prim)(assertion("prim")() { v =>
          !testUnboxed || (v._1.equals(value) && v._2.getTypeName.equals(pType.getTypeName))
        }) &&
        assert(boxed)(assertion(name = "boxed")() {
          case None    => true
          case Some(v) => v._1.equals(box(value)) && v._2.getTypeName.equals(pType.getTypeName)
        })
      })

    Seq(
      testPrimitive[Boolean](Gen.boolean, SimpleType.BOOLEAN, Boolean.box),
      testPrimitive[Short](Gen.anyShort, SimpleType.SHORT, Short.box),
      testPrimitive[Int](Gen.anyInt, SimpleType.INTEGER, Int.box),
      testPrimitive[Long](Gen.anyLong, SimpleType.LONG, Long.box),
      testPrimitive[Float](Gen.anyFloat, SimpleType.FLOAT, Float.box),
      testPrimitive[Double](Gen.anyDouble, SimpleType.DOUBLE, Double.box),
      testPrimitive[String](genSomeString, SimpleType.STRING),
      testPrimitive[BigDecimal](genSomeBigDecimal, SimpleType.BIGDECIMAL, (x: BigDecimal) => x.bigDecimal, false),
      testPrimitive[BigInt](genSomeBigInt, SimpleType.BIGINTEGER, (x: BigInt) => x.bigInteger, false),
      testPrimitive[jm.BigDecimal](Gen.anyDouble.map(new jm.BigDecimal(_)), SimpleType.BIGDECIMAL),
      testPrimitive[jm.BigInteger](Gen.anyLong.map(i => new jm.BigInteger(i.toString, 10)), SimpleType.BIGINTEGER),
      testPrimitive[ObjectName](genObjectName, SimpleType.OBJECTNAME),
      testPrimitive[Date](genSomeDate, SimpleType.DATE)
    )
  }

  private val testArrays = {
    def testJavaArrays[T](gen: Gen[Random, T], pType: SimpleType[_])(implicit cTag: ClassTag[T]) = {
      val rcClass          = cTag.runtimeClass
      val isPrim           = rcClass.isPrimitive
      val typeName: String = if (isPrim) s"Primitive($pType)" else s"(ClassTag($pType))"
      val expectedType     = new ArrayType(pType, isPrim)

      testM(s"map array of [$typeName]")(check(Gen.listOfBounded(0, 10)(gen).map(_.toArray)) { value =>
        val mapped = mapper.fieldToElement("arr", value)
        assert(mapped._2.getTypeName)(equalTo(expectedType.getTypeName))
      })
    }

    Seq(
      testJavaArrays[jl.Boolean](Gen.boolean.map(b => if (b) true else false), SimpleType.BOOLEAN),
      testJavaArrays[jl.Byte](Gen.anyByte.map(jl.Byte.valueOf), SimpleType.BYTE),
      testJavaArrays[jl.Short](Gen.anyShort.map(jl.Short.valueOf), SimpleType.SHORT),
      testJavaArrays[jl.Integer](Gen.anyInt.map(jl.Integer.valueOf), SimpleType.INTEGER),
      testJavaArrays[jl.Long](Gen.anyLong.map(jl.Long.valueOf), SimpleType.LONG),
      testJavaArrays[jl.Float](Gen.anyFloat.map(jl.Float.valueOf), SimpleType.FLOAT),
      testJavaArrays[jl.Double](Gen.anyDouble.map(jl.Double.valueOf), SimpleType.DOUBLE),
      testJavaArrays[Boolean](Gen.boolean, SimpleType.BOOLEAN),
      testJavaArrays[Byte](Gen.anyByte, SimpleType.BYTE),
      testJavaArrays[Short](Gen.anyShort, SimpleType.SHORT),
      testJavaArrays[Int](Gen.anyInt, SimpleType.INTEGER),
      testJavaArrays[Long](Gen.anyLong, SimpleType.LONG),
      testJavaArrays[Float](Gen.anyFloat, SimpleType.FLOAT),
      testJavaArrays[Double](Gen.anyDouble, SimpleType.DOUBLE),
      testJavaArrays[String](genSomeString, SimpleType.STRING),
      testJavaArrays[BigDecimal](genSomeBigDecimal, SimpleType.BIGDECIMAL),
      testJavaArrays[jm.BigDecimal](Gen.anyDouble.map(new jm.BigDecimal(_)), SimpleType.BIGDECIMAL),
      testJavaArrays[BigInt](genSomeBigInt, SimpleType.BIGINTEGER),
      testJavaArrays[jm.BigInteger](Gen.anyLong.map(l => new jm.BigInteger(l.toString, 10)), SimpleType.BIGINTEGER),
      testJavaArrays[Date](genSomeDate, SimpleType.DATE)
    )
  }

  private val testSeqs = {

    def testScalaSeq[T](gen: Gen[Random, T], pType: SimpleType[_])(implicit cTag: ClassTag[T]) = {
      val rcClass          = cTag.runtimeClass
      val isPrim           = rcClass.isPrimitive
      val typeName: String = if (isPrim) s"Primitive($pType)" else s"(ClassTag($pType))"

      testM(s"map Scala sequence of $typeName")(check(Gen.listOfBounded(0, 10)(gen).map(_.toSeq)) { value =>
        val mapped = mapper.fieldToElement("seq", value)

        assert(mapped)(assertion("tabType")() { v =>
          if (value.isEmpty) {
            v._2.getTypeName.equals(SimpleType.VOID.getTypeName)
          } else {
            v._1.isInstanceOf[TabularData] &&
            v._1.asInstanceOf[TabularData].getTabularType().getIndexNames().asScala == List("index") &&
            v._1.asInstanceOf[TabularData].size() == value.size
          }
        })
      })
    }

    Seq(
      testScalaSeq[jl.Boolean](Gen.boolean.map(jl.Boolean.valueOf), SimpleType.BOOLEAN),
      testScalaSeq[jl.Byte](Gen.anyByte.map(jl.Byte.valueOf), SimpleType.BYTE),
      testScalaSeq[jl.Short](Gen.anyShort.map(jl.Short.valueOf), SimpleType.SHORT),
      testScalaSeq[jl.Integer](Gen.anyInt.map(jl.Integer.valueOf), SimpleType.INTEGER),
      testScalaSeq[jl.Long](Gen.anyLong.map(jl.Long.valueOf), SimpleType.LONG),
      testScalaSeq[jl.Float](Gen.anyFloat.map(jl.Float.valueOf), SimpleType.FLOAT),
      testScalaSeq[jl.Double](Gen.anyDouble.map(jl.Double.valueOf), SimpleType.DOUBLE),
      testScalaSeq[Boolean](Gen.boolean, SimpleType.BOOLEAN),
      testScalaSeq[Byte](Gen.anyByte, SimpleType.BYTE),
      testScalaSeq[Short](Gen.anyShort, SimpleType.SHORT),
      testScalaSeq[Int](Gen.anyInt, SimpleType.INTEGER),
      testScalaSeq[Long](Gen.anyLong, SimpleType.LONG),
      testScalaSeq[Float](Gen.anyFloat, SimpleType.FLOAT),
      testScalaSeq[Double](Gen.anyDouble, SimpleType.DOUBLE),
      testScalaSeq[Date](genSomeDate, SimpleType.DATE),
      testScalaSeq[jm.BigDecimal](Gen.anyDouble.map(f => jm.BigDecimal.valueOf(f)), SimpleType.BIGDECIMAL),
      testScalaSeq[jm.BigInteger](Gen.anyLong.map(l => new jm.BigInteger(l.toString, 10)), SimpleType.BIGINTEGER)
    )
  }

  private val testMaps = {

    def testScalaMap[T](gen: Gen[Random, T], pType: SimpleType[_])(implicit cTag: ClassTag[T]) = {
      val rcClass          = cTag.runtimeClass
      val isPrim           = rcClass.isPrimitive
      val typeName: String = if (isPrim) s"Primitive($pType)" else s"(ClassTag($pType))"

      testM(s"map Scala Map of $typeName")(check(Gen.mapOfBounded(0, 10)(gen, gen)) { value =>
        val mapped = mapper.fieldToElement("seq", value)

        assert(mapped)(assertion("tabType")() { v =>
          if (value.isEmpty) {
            v._2.getTypeName.equals(SimpleType.VOID.getTypeName)
          } else {
            v._1.isInstanceOf[TabularData] &&
            v._1.asInstanceOf[TabularData].getTabularType().getIndexNames().asScala == List("index") &&
            v._1.asInstanceOf[TabularData].size() == value.size
          }
        })
      })
    }

    Seq(
      testScalaMap[jl.Boolean](Gen.boolean.map(jl.Boolean.valueOf), SimpleType.BOOLEAN),
      testScalaMap[jl.Byte](Gen.anyByte.map(jl.Byte.valueOf), SimpleType.BYTE),
      testScalaMap[jl.Short](Gen.anyShort.map(jl.Short.valueOf), SimpleType.SHORT),
      testScalaMap[jl.Integer](Gen.anyInt.map(jl.Integer.valueOf), SimpleType.INTEGER),
      testScalaMap[jl.Long](Gen.anyLong.map(jl.Long.valueOf), SimpleType.LONG),
      testScalaMap[jl.Float](Gen.anyFloat.map(jl.Float.valueOf), SimpleType.FLOAT),
      testScalaMap[jl.Double](Gen.anyDouble.map(jl.Double.valueOf), SimpleType.DOUBLE),
      testScalaMap[Boolean](Gen.boolean, SimpleType.BOOLEAN),
      testScalaMap[Byte](Gen.anyByte, SimpleType.BYTE),
      testScalaMap[Short](Gen.anyShort, SimpleType.SHORT),
      testScalaMap[Int](Gen.anyInt, SimpleType.INTEGER),
      testScalaMap[Long](Gen.anyLong, SimpleType.LONG),
      testScalaMap[Float](Gen.anyFloat, SimpleType.FLOAT),
      testScalaMap[Double](Gen.anyDouble, SimpleType.DOUBLE),
      testScalaMap[Date](genSomeDate, SimpleType.DATE),
      testScalaMap[jm.BigDecimal](Gen.anyDouble.map(new jm.BigDecimal(_)), SimpleType.BIGDECIMAL),
      testScalaMap[jm.BigInteger](Gen.anyLong.map(l => new jm.BigInteger(l.toString, 10)), SimpleType.BIGINTEGER)
    )
  }

  private val testJavaColls = {
    def testJavaColl[T](gen: Gen[Random, T], pType: SimpleType[_])(implicit cTag: ClassTag[T]) = {
      val rcClass          = cTag.runtimeClass
      val isPrim           = rcClass.isPrimitive
      val typeName: String = if (isPrim) s"Primitive($pType)" else s"(ClassTag($pType))"

      testM(s"map Java Collection of $typeName")(check(Gen.listOfBounded(0, 10)(gen).map(_.asJava)) { value =>
        val mapped = mapper.fieldToElement("seq", value)

        assert(mapped)(assertion("tabType")() { v =>
          if (value.isEmpty) {
            v._2.getTypeName.equals(SimpleType.VOID.getTypeName)
          } else {
            v._1.isInstanceOf[TabularData] &&
            v._1.asInstanceOf[TabularData].getTabularType().getIndexNames().asScala == List("index") &&
            v._1.asInstanceOf[TabularData].size() == value.size
          }
        })
      })
    }

    Seq(
      testJavaColl[jl.Boolean](Gen.boolean.map(jl.Boolean.valueOf), SimpleType.BOOLEAN),
      testJavaColl[jl.Byte](Gen.anyByte.map(jl.Byte.valueOf), SimpleType.BYTE),
      testJavaColl[jl.Short](Gen.anyShort.map(jl.Short.valueOf), SimpleType.SHORT),
      testJavaColl[jl.Integer](Gen.anyInt.map(jl.Integer.valueOf), SimpleType.INTEGER),
      testJavaColl[jl.Long](Gen.anyLong.map(jl.Long.valueOf), SimpleType.LONG),
      testJavaColl[jl.Float](Gen.anyFloat.map(jl.Float.valueOf), SimpleType.FLOAT),
      testJavaColl[jl.Double](Gen.anyDouble.map(jl.Double.valueOf), SimpleType.DOUBLE),
      testJavaColl[Boolean](Gen.boolean, SimpleType.BOOLEAN),
      testJavaColl[Byte](Gen.anyByte, SimpleType.BYTE),
      testJavaColl[Short](Gen.anyShort, SimpleType.SHORT),
      testJavaColl[Int](Gen.anyInt, SimpleType.INTEGER),
      testJavaColl[Long](Gen.anyLong, SimpleType.LONG),
      testJavaColl[Float](Gen.anyFloat, SimpleType.FLOAT),
      testJavaColl[Double](Gen.anyDouble, SimpleType.DOUBLE),
      testJavaColl[Date](genSomeDate, SimpleType.DATE),
      testJavaColl[jm.BigDecimal](Gen.anyDouble.map(new jm.BigDecimal(_)), SimpleType.BIGDECIMAL),
      testJavaColl[jm.BigInteger](Gen.anyLong.map(l => new jm.BigInteger(l.toString, 10)), SimpleType.BIGINTEGER)
    )
  }

  private val testJavaMaps = {
    def testJavaMap[T](gen: Gen[Random, T], pType: SimpleType[_])(implicit cTag: ClassTag[T]) = {
      val rcClass          = cTag.runtimeClass
      val isPrim           = rcClass.isPrimitive
      val typeName: String = if (isPrim) s"Primitive($pType)" else s"(ClassTag($pType))"

      testM(s"map Java Collection of $typeName")(check(Gen.mapOfBounded(0, 10)(gen, gen).map(_.asJava)) { value =>
        val mapped = mapper.fieldToElement("seq", value)

        assert(mapped)(assertion("tabType")() { v =>
          if (value.isEmpty) {
            v._2.getTypeName.equals(SimpleType.VOID.getTypeName)
          } else {
            v._1.isInstanceOf[TabularData] &&
            v._1.asInstanceOf[TabularData].getTabularType().getIndexNames().asScala == List("index") &&
            v._1.asInstanceOf[TabularData].size() == value.size
          }
        })
      })
    }

    Seq(
      testJavaMap[jl.Boolean](Gen.boolean.map(jl.Boolean.valueOf), SimpleType.BOOLEAN),
      testJavaMap[jl.Byte](Gen.anyByte.map(jl.Byte.valueOf), SimpleType.BYTE),
      testJavaMap[jl.Short](Gen.anyShort.map(jl.Short.valueOf), SimpleType.SHORT),
      testJavaMap[jl.Integer](Gen.anyInt.map(jl.Integer.valueOf), SimpleType.INTEGER),
      testJavaMap[jl.Long](Gen.anyLong.map(jl.Long.valueOf), SimpleType.LONG),
      testJavaMap[jl.Float](Gen.anyFloat.map(jl.Float.valueOf), SimpleType.FLOAT),
      testJavaMap[jl.Double](Gen.anyDouble.map(jl.Double.valueOf), SimpleType.DOUBLE),
      testJavaMap[Boolean](Gen.boolean, SimpleType.BOOLEAN),
      testJavaMap[Byte](Gen.anyByte, SimpleType.BYTE),
      testJavaMap[Short](Gen.anyShort, SimpleType.SHORT),
      testJavaMap[Int](Gen.anyInt, SimpleType.INTEGER),
      testJavaMap[Long](Gen.anyLong, SimpleType.LONG),
      testJavaMap[Float](Gen.anyFloat, SimpleType.FLOAT),
      testJavaMap[Double](Gen.anyDouble, SimpleType.DOUBLE),
      testJavaMap[Date](genSomeDate, SimpleType.DATE),
      testJavaMap[jm.BigDecimal](Gen.anyDouble.map(new jm.BigDecimal(_)), SimpleType.BIGDECIMAL),
      testJavaMap[jm.BigInteger](Gen.anyLong.map(l => new jm.BigInteger(l.toString, 10)), SimpleType.BIGINTEGER)
    )
  }

  val testCaseClass: ZSpec[TestConfig with Random, Nothing] = testM("map case classes")(
    check(
      genSomeString,
      Gen.anyInt,
      Gen.listOfBounded(0, 10)(genSomeString),
      Gen.listOfBounded(0, 10)(genSomeString),
      Gen.mapOfBounded(0, 10)(genSomeString, genSomeString)
    ) { (s, i, sl1, sl2, ms) =>
      val cc     = CaseClass1(s, i, sl1.toArray, sl2.toSeq, ms)
      val mapped = mapper.mapProduct(cc)

      assert(mapped)(assertion("beanInfo")() { m =>
        val attrs: List[MBeanAttributeInfo] = m.getMBeanInfo.getAttributes.toList

        val keys: List[String]       = attrs.map(_.getName).sorted
        val fieldNames: List[String] = cc.getClass.getDeclaredFields.toList.map(_.getName).sorted

        m.getMBeanInfo.getAttributes.length == cc.productArity &&
        keys.equals(fieldNames)
      })
    }
  )
}

private case class CaseClass1(
  aString: String,
  aInt: Int,
  aStringArray: Array[String],
  aStringSeq: Seq[String],
  aStringMap: Map[String, String]
)
