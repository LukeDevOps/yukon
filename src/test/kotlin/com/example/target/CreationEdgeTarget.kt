package com.example.target

import java.util.function.IntUnaryOperator

/**
 * Each shape of creation edge kotlinc emits (ADR 0034). kotlinc compiles each lambda here to a
 * private static `<method>$lambda$N` body, reached through an `invokedynamic` whose bootstrap is
 * `LambdaMetafactory`. The one in [nested] creates a second lambda inside its own body. The one in
 * [capturing] takes `offset` as a leading parameter, and the one in [capturingThis] takes the
 * receiver the same way. [samReference] hands a named method to a Java functional interface, and
 * [makesBodyClass] creates an object expression. [callsDefault] reaches a lambda through the
 * `$default` method that fills in [withDefault]'s default, a pass-through the call goes through.
 */
class CreationEdgeTarget {
    private val base = 10

    fun plain(): Int = applyOp({ it + 1 }, 5)

    fun capturing(offset: Int): Int = applyOp({ it + offset }, 5)

    fun capturingThis(): Int = applyOp({ it + base }, 5)

    fun nested(): Int = applyOp({ outer -> applyOp({ inner -> inner + outer }, outer) }, 1)

    fun samReference(): Int = IntUnaryOperator(::twice).applyAsInt(3)

    fun twice(value: Int): Int = value * 2

    fun withDefault(op: (Int) -> Int = { it * 3 }): Int = op(2)

    fun callsDefault(): Int = withDefault()

    fun makesBodyClass(): Runnable =
        object : Runnable {
            override fun run() {
                twice(1)
            }
        }
}
