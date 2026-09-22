package com.example.target.keypairs

/** One condition on `x`, nothing before it in the method. */
class DifferentConditionalAddedEarlierV1 {
    fun check(
        x: Int,
        y: Int,
    ): String = if (x > 0) "pos" else "non-pos"
}

/** A different, unrelated condition on `y` is added earlier in the same method; `x`'s condition is unchanged. */
class DifferentConditionalAddedEarlierV2 {
    fun check(
        x: Int,
        y: Int,
    ): String {
        if (y > 0) println("y is positive")
        return if (x > 0) "pos" else "non-pos"
    }
}
