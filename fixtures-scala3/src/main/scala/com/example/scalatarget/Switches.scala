package com.example.scalatarget

/** The enum the Scala 3 switch fixtures match on. See agent ADR 0038. */
enum Hue {
  case Red, Green, Blue
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

  def enumMatch(hue: Hue): Int = hue match {
    case Hue.Red  => 1
    case Hue.Blue => 3
    case _        => 0
  }

  def enumExhaustive(hue: Hue): Int = hue match {
    case Hue.Red   => 1
    case Hue.Green => 2
    case Hue.Blue  => 3
  }
}
