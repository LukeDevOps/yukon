package com.example.scalatarget

/**
 * A case class with an auxiliary constructor, and a hand-written `copy` and companion `apply` that
 * each construct through it. Only scalac's `copy(Int)` and `apply(Int)` use the primary constructor.
 */
case class Aux(a: Int) {
  def this(s: String) = this(s.toInt)
  def copy(s: String): Aux = new Aux(s)
}

object Aux {
  def apply(s: String): Aux = new Aux(s)
}

/** A supertype holding a value its case-class subclass also passes up. */
abstract class Base(val a: Int)

/**
 * A case class whose element overrides the supertype's `val`. Scala 2.13.15's constructor passes
 * `a` to `Base` and stores only the second list's `b`.
 */
case class Q(override val a: Int)(val b: Int) extends Base(a)

/** A case class whose hand-written `productArity` returns a count that is not its element count. */
case class Lie(a: Int) {
  override def productArity: Int = 3
}

/** A case class with a hand-written `_1`, a name Scala 2.13.15 never generates. */
case class Al(a: Int, b: String) {
  def _1: Int = a
}

/** A case class whose hand-written `equals` has scalac's shape but compares only `a`. */
case class E3(a: Int, b: String) {
  override def equals(o: Any): Boolean = (this eq o.asInstanceOf[AnyRef]) || (o match {
    case that: E3 => a == that.a && that.canEqual(this)
    case _ => false
  })
}

/** A case class with three elements of one primitive type. */
case class P3(x: Double, y: Double, z: Double)

/** A case class whose hand-written `productArity` returns fewer than its two elements. */
case class Lie2(a: Int, b: Int) {
  override def productArity: Int = 1
}

/** A case class whose primary constructor builds another instance of its own class. */
case class Node(a: Int) {
  val child: Node = if (a > 0) new Node(a - 1) else null
}

/** A case class whose body holds a try/catch, which scalac puts in the primary constructor. */
case class TryBody(a: Int) {
  try { println(a) }
  catch { case _: Exception => () }
}

/** Final case classes, whose `equals` scalac writes without a call to `canEqual`. */
final case class FT(a: Int, b: String)

final case class FE()

final case class F1(s: String)

/** A class holding a final inner case class. */
class FinalOuter {
  final case class FIn(a: Int, b: String)

  final case class FInEmpty()
}

/** An object holding a final case class. */
object FinalHolder {
  final case class FObj(a: Int)
}

/**
 * A case class that is not final, whose hand-written `equals` compares every element but never
 * calls `canEqual`, the shape scalac writes only for a final class.
 */
case class NoCanEqual(a: Int, b: String) {
  override def equals(o: Any): Boolean = (this eq o.asInstanceOf[AnyRef]) || (o match {
    case that: NoCanEqual => a == that.a && b == that.b
    case _ => false
  })
}

/** A final case class with its own `canEqual`, so scalac's `equals` still calls it. */
final case class UC(a: Int, b: String) {
  override def canEqual(o: Any): Boolean = o.isInstanceOf[UC]
}

/**
 * A final case class with its own `canEqual` and a hand-written `equals` that has the shape
 * scalac writes for a final class whose `canEqual` is scalac's.
 */
final case class UC2(a: Int, b: String) {
  override def canEqual(o: Any): Boolean = false
  override def equals(o: Any): Boolean = (this eq o.asInstanceOf[AnyRef]) || (o match {
    case that: UC2 => a == that.a && b == that.b
    case _ => false
  })
}

/** A final case class with no elements and its own `canEqual`. */
final case class FZC() {
  override def canEqual(o: Any): Boolean = false
}
