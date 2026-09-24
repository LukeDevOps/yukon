package com.example.scalatarget

/** The sealed hierarchy the Scala 2 switch fixtures match on. See agent ADR 0038. */
sealed trait Shade
case object Light extends Shade
case object Dark extends Shade
case object Dim extends Shade

/** The enumeration the Scala 2 switch fixtures match on. See agent ADR 0038. */
object Level extends Enumeration {
  val Low, Mid, High = Value
}

/** Each scalac lowering of a match on a string and on an enum. See agent ADR 0038. */
class Switches {
  def stringMatch(status: String): Int = status match {
    case "open"            => 1
    case "closed" | "done" => 2
    case "Aa"              => 3
    case "BB"              => 4
    case _                 => 0
  }

  def sealedMatch(shade: Shade): Int = shade match {
    case Light => 1
    case Dark  => 2
    case Dim   => 3
  }

  def enumerationMatch(level: Level.Value): Int = level match {
    case Level.Low  => 1
    case Level.High => 3
    case _          => 0
  }
}
