package com.example.scalatarget

/**
 * A plain class whose `f` has two optional parameters plus a required one, next to an
 * overload without any defaults. `f$default$2` must resolve against `f(I I Ljava/lang/String;)`
 * and not against the overload `f(I J)`, since only the three-parameter overload's second
 * parameter erases to the getter's return type.
 */
class Simple {
  def f(a: Int, b: Int = 1, c: String = "x"): Int = a + b + c.length
  def f(a: Int, b: Long): Int = a + b.toInt
}

/** A curried default whose value depends on an earlier parameter list. */
class Curried {
  def g(a: Int)(b: Int = a + 1): Int = a + b
}

/** A by-name default: its getter returns `scala.Function0`, not the value type directly. */
class ByNameHost {
  def h(a: => Int = 1): Int = a
}

/** A default whose parameter type is a generic type parameter, erasing to `Object`. */
class Generic {
  def firstOrNull[A >: Null <: AnyRef](a: A = null.asInstanceOf[A]): A = a
}

/** A `final` method with a default, inside a non-final class. */
class FinalMethodHost {
  final def finalF(a: Int, b: Int = 2): Int = a + b
}

/** A `private` method with a default, called only from a public method in the same class. */
class PrivateMethodHost {
  private def priv(a: Int, b: Int = 3): Int = a + b
  def callPriv(a: Int): Int = priv(a)
}

/** A method with a default in each of two parameter lists. */
class TwoListsHost {
  def twoLists(a: Int = 1)(b: Int = 2): Int = a + b
}

/** Two overloads sharing a name but not arity; only the three-parameter one has a default. */
class SameNameDiffArityHost {
  def sameNameDiffArity(a: Int): Int = a
  def sameNameDiffArity(a: Int, b: Int, c: Int = 1): Int = a + b + c
}

/**
 * A default parameter followed by a repeated parameter. Scala forbids a default in the same
 * parameter section as a `*`-parameter, so the default and the vararg sit in their own sections.
 */
class VarargHost {
  def withVararg(a: Int = 1)(rest: Int*): Int = a + rest.sum
}

/** A trait with a defaulted method, and a class that implements it without overriding `t`. */
trait Tr {
  def t(a: Int = 1): Int = a
}
class TrImpl extends Tr

/** An `object` with a defaulted method; module classes are final, so `m` is never overridable. */
object Obj {
  def m(a: Int, b: Int = 4): Int = a + b
}

/** A case class with two defaulted constructor parameters. */
case class Cc(a: Int = 1, b: Int = 2)

/** An open base whose `f` a subclass may or may not also override. */
class Plain {
  def f(a: Int = 1): Int = a
}

/** Overrides both `f` and its own default value. */
class PlainOverridesBoth extends Plain {
  override def f(a: Int = 2): Int = a * 2
}

/** Overrides `f` but declares no default of its own; callers still use `Plain`'s getter. */
class PlainOverridesOnly extends Plain {
  override def f(a: Int): Int = a * 3
}

/** A final class hosting an otherwise ordinary defaulted method. */
final class FinalHost {
  def f(a: Int, b: Int = 5): Int = a + b
}

/**
 * A lambda body with a conditional, compiled by scalac to a synthetic `$anonfun$classify$1`
 * method on the module class. Proves that the method and branch tiers probe a scalac lambda body
 * too, while its boxing adapter and any unrelated synthetic method on a non-Scala class stay out.
 */
object LambdaHost {
  def classify(value: Int): String = {
    val f: Int => String = v => if (v > 0) "positive" else "non-positive"
    f(value)
  }
}
