package blended.zio.jmx.publish

import blended.zio.jmx.JmxObjectName

trait Nameable[A]     {
  def objectName(v: A): JmxObjectName
}
object NameableSyntax {
  implicit class NameableOps[A <: Product](v: A) {
    def objectName(implicit f: A => Nameable[A]): JmxObjectName = f(v).objectName(v)
  }
}
