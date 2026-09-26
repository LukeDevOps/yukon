package com.example.scalatarget

/** A case class with no elements whose companion's hand-written `unapply` returns a constant `true`. */
case class U0()

object U0 {
  def unapply(x: U0): Boolean = true
}

/** A case class whose companion's hand-written `unapply` returns its argument. */
case class Id(s: String)

object Id {
  def unapply(x: Id): Id = x
}

/** A case class whose companion has a hand-written `fromProduct`, a name Scala 2.13.15 never generates. */
case class FP(a: Int)

object FP {
  def fromProduct(p: Product): FP = new FP(p.productElement(0).asInstanceOf[Int])
}
