package com.example.scalatarget

/** Fixtures for the condition writer's Scala idioms. Each method holds one conditional. See agent ADR 0037. */
class Conditions {
  def anyEquals(a: Any, b: Any): Int = if (a == b) 1 else 0

  def stringEquals(a: String, b: String): Int = if (a == b) 1 else 0

  def referenceEq(a: AnyRef, b: AnyRef): Int = if (a eq b) 1 else 0

  def referenceNe(a: AnyRef, b: AnyRef): Int = if (a ne b) 1 else 0

  def boxedCompare(a: Any): Int = if (a.asInstanceOf[Int] > 3) 1 else 0

  def intCompare(a: Int, b: Int): Int = if (a > b) 1 else 0

  def instanceCheck(a: Any): Int = if (a.isInstanceOf[String]) 1 else 0

  def literalCheck(text: String): Int = if (text == "say \"hi\"\nbye") 1 else 0

  def narrowing(total: Long): Int = if (total.toInt > 3) 1 else 0

  def widening(count: Int, limit: Long): Int = if (count < limit) 1 else 0
}
