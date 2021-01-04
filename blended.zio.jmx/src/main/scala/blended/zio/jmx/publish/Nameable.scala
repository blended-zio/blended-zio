package blended.zio.jmx.publish

import blended.zio.jmx.JmxObjectName

trait Nameable[A <: Product] {
  def objectName(v: A): JmxObjectName
}

object Nameable {
  def objectName[A <: Product](v: A)(implicit f: A => Nameable[A]): JmxObjectName =
    f(v).objectName(v)
}

object NameableSyntax {
  implicit class NameableOps[A <: Product](v: A) {
    def objectName(implicit f: A => Nameable[A]): JmxObjectName = f(v).objectName(v)
  }
}
