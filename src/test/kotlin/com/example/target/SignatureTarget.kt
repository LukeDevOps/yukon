package com.example.target

import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Shapes whose parameter names, generic signature and extension receiver ADR 0043 reads from the
 * class file. The private function has no nullability annotations, and its names still come through.
 */
private fun formatTotal(
    total: Double,
    currency: String,
    decimals: Int,
): String = "%.${decimals}f $currency".format(total)

/** Calls [formatTotal], so the private function is not dead to the compiler. */
fun receipt(total: Double): String = formatTotal(total, "GBP", 2)

/** A generic function: its `Signature` attribute keeps `T`, which the descriptor erases. */
fun <T> firstOf(items: List<T>): T = items.first()

/** No parameters, but a generic return type the descriptor erases to `List`. */
fun orders(): List<String> = listOf("a", "b")

/** An extension function: kotlinc names its receiver parameter `$this$shout`. */
fun String.shout(): String = uppercase() + "!"

/** A top-level function whose `long` and `double` take two slots each. */
fun mix(
    count: Long,
    ratio: Double,
    last: Int,
): String = "$count $ratio $last"

/** A suspend function with a suspension point, so kotlinc builds a state machine for it. */
suspend fun loadName(id: Int): String {
    val offset = suspendCoroutineUninterceptedOrReturn<Int> { 1 }
    return "user-${id + offset}"
}

/** A non-inline lambda that captures [prefix], so its body takes the captured value first. */
fun prefixAll(
    prefix: String,
    items: List<String>,
): List<String> = items.asSequence().map { prefix + it }.toList()

/** A class with a constructor, an instance method and an instance method with wide slots. */
class SignatureTarget(
    private val id: Long,
    private val rate: Double,
) {
    /** An instance method: slot 0 is `this`, so `label` starts at slot 1. */
    fun describe(
        label: String,
        count: Int,
    ): String = "$label $id $rate $count"

    /** An instance method whose `long` and `double` take two slots each after `this`. */
    fun scale(
        factor: Long,
        weight: Double,
        tag: String,
    ): String = "$tag ${id * factor} ${rate * weight}"
}
