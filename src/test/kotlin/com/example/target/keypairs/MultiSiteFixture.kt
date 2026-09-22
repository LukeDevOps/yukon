package com.example.target.keypairs

/**
 * Several methods and site shapes in one class, used to check that branch keys stay unique
 * within a class under JaCoCo instrumentation and that analysing the same bytes twice gives the
 * same keys.
 */
class MultiSiteFixture {
    fun first(x: Int): String = if (x > 0) "pos" else "non-pos"

    fun second(
        a: Int,
        b: Int,
    ): String = if (a > 0 && b > 0) "both" else "not-both"

    fun classify(x: Int): String =
        when (x) {
            1 -> "one"
            2 -> "two"
            else -> "other"
        }
}
