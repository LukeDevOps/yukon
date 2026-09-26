package com.example.scalatarget

/**
 * A case class whose parameters sit on lines of their own. ADR 0048 marks its plumbing by body
 * shape whatever the layout. The `toString` in the body is the adopter's.
 */
case class Multi(
    a: Int,
    b: String
) {
  override def toString: String =
    s"Multi($a)"
}

/**
 * A sealed trait that brings `Product` itself, so Scala 2.13 leaves `scala.Product` out of each
 * case class's own interface list.
 */
sealed trait Figure extends Product with Serializable

case class Round(r: Double) extends Figure

case class Box(w: Double, h: Double) extends Figure

/** A case class with no parameters, whose companion's `unapply` returns a `boolean`. */
case class Empty()

/** A case object: its module class carries the case-class plumbing itself. */
case object Solo

/**
 * A case class whose body holds a `val` after a hand-written override. scalac puts the `val`'s
 * initialiser in the constructor and gives the `val` a field after the element's.
 */
case class BodyVal(a: Int) {
  override def toString: String =
    "BodyVal"
  val later: Int = a + 1
}

/**
 * A case class with a hand-written `copy`, so scalac writes none. The `toString`, `hashCode` and
 * `copy` are the adopter's, but the `copy`'s body is instruction for instruction the one scalac
 * would have written, so ADR 0048 marks it as generated.
 */
case class HandCopy(
    a: Int
) {
  override def toString: String =
    "HandCopy"
  override def hashCode: Int =
    7
  def copy(a: Int = 2): HandCopy =
    new HandCopy(a)
}

/** A type of the fixtures' own for [[Svc]]'s implicit parameter. */
class Pool(val size: Int)

/** A case class whose first parameter list and implicit second list both span several lines. */
case class Svc(
    a: Int,
    b: String
)(implicit
    pool: Pool
) {
  override def toString: String =
    "Svc"
}

/** A case class with two parameter lists, each on lines of its own. */
case class TwoLists(
    a: Int
)(
    b: Int
)

/**
 * A class holding an inner case class and, in a method, a local one. Neither companion has a
 * static `MODULE$`.
 */
class Outer {
  case class Inner(a: Int)

  def local(): Any = {
    case class Local(a: Int)
    Local(1)
  }
}

/** A case class with a `private` and a `protected` parameter, for which Scala 2 writes `$access$N` accessors. */
case class Priv(
    private val a: Int,
    protected val b: String
)

/**
 * A case class with a field of every primitive type and three references, one of them generic, so
 * `hashCode`, `equals` and `productElement` carry each per-type conversion scalac writes.
 */
case class Mixed[T](
    l: Long,
    d: Double,
    z: Boolean,
    s: String,
    i: Int,
    f: Float,
    c: Char,
    by: Byte,
    sh: Short,
    t: T,
    o: Option[Int]
)

/** A case class with a single field. */
case class One(s: String)

/** A case class whose only `private` field is a `long`, read through Scala 2's `$access$0`. */
case class Hidden(private val secret: Long, open: Boolean)

/** A case class with a `lazy val` in its body, whose field Scala 2.13.15 declares before the elements' fields. */
case class Lazy(a: Int, b: String) {
  lazy val twice: Int = a * 2
}
