package blended.zio.core.evaluator

abstract class EvaluatorException(msg: String) extends Exception(msg)

/**
 * A generic Exception class used by all Modifiers called from within the [[StringEvaluator]]
 */
class ModifierException(
  val mod: Modifier,
  val segment: String,
  val param: String,
  val msg: String
) extends EvaluatorException(s"Modifier [${mod.name}] failed for [$segment] with parameter [$param] : $msg")

/**
 * An exception used to signal that a String could not be evaluated because it could not be parsed correctly.
 */
class InvalidFormatException(
  line: String,
  msg: String
) extends EvaluatorException(s"Error evaluating [$line] : $msg")

/**
 * An exception signalling that a required variable does not exist within the context
 */
class UnresolvedVariableException(
  val line: String,
  val varName: String
) extends EvaluatorException(s"Unable to resolve variable [$varName] in [$line]")

class UnknownModifierException(
  val line: String,
  val modName: String
) extends EvaluatorException(s"Unknown modifier [$modName] referenced in [$line]")
