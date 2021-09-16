package blended.zio.jmx.publish

import blended.zio.jmx.JmxObjectName

trait Nameable[A] {
  def objectName(v: A): JmxObjectName
}

object NameableSyntax {
  implicit class NameableOps[A: Nameable](v: A) {
    def objectName: JmxObjectName = {
      val n = implicitly[Nameable[A]]
      n.objectName(v)
    }
  }
}
