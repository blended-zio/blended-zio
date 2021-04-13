package blended.zio.streams.jms

import zio._
import blended.zio.streams.MsgProperty

sealed trait JmsMessageSelector {
  val selector: String
}

object JmsMessageSelector {

  sealed trait Operator {
    def op: String
  }
  final case object Equal          extends Operator { override val op = "="          }
  final case object Unequal        extends Operator { override def op: String = "<>" }
  final case object LessThan       extends Operator { override def op: String = "<"  }
  final case object LessOrEqual    extends Operator { override def op: String = "<=" }
  final case object GreaterThan    extends Operator { override def op: String = ">"  }
  final case object GreaterOrEqual extends Operator { override def op: String = ">=" }

  final case class SimpleSelector(field: String, op: Operator, param: MsgProperty[_]) extends JmsMessageSelector {
    override val selector: String = s"$field ${op.op} ${paramAsString(param)}"
  }
  final case class InSelector(field: String, elems: Chunk[String])                    extends JmsMessageSelector {
    override val selector: String = s"$field IN (${elems.map(s => s"'$s'").mkString(",")})"
  }
  final case class LikeSelector(field: String, comp: String)                          extends JmsMessageSelector {
    override val selector: String = s"$field LIKE '$comp'"
  }
  final case class AndSelector(left: JmsMessageSelector, right: JmsMessageSelector)   extends JmsMessageSelector {
    override val selector: String = s"(${left.selector}) AND (${right.selector})"
  }
  final case class OrSelector(left: JmsMessageSelector, right: JmsMessageSelector)    extends JmsMessageSelector {
    override val selector: String = s"(${left.selector}) OR (${right.selector})"
  }
  final case class NotSelector(s: JmsMessageSelector)                                 extends JmsMessageSelector {
    override val selector: String = s"NOT (${s.selector})"
  }

  private def paramAsString(p: MsgProperty[_]): String = p.value match {
    case s: String  => s"'$s'"
    case c: Char    => s"'$c'"
    case b: Boolean => b.toString.toUpperCase
    case o          => o.toString
  }

  implicit class JmsSelectorOps(self: JmsMessageSelector) {
    def or(that: JmsMessageSelector): JmsMessageSelector  = OrSelector(self, that)
    def and(that: JmsMessageSelector): JmsMessageSelector = AndSelector(self, that)
    def not: JmsMessageSelector                           = NotSelector(self)
  }
}
