package com.example.target.keypairs

/** No conditional in `first`; `second` holds the one tracked site. */
class EarlierMethodEditV1 {
    fun first(x: Int): Int = x + 1

    fun second(x: Int): String = if (x > 0) "pos" else "non-pos"
}

/** `first`, which comes earlier in the class, gains a conditional; `second` is unchanged. */
class EarlierMethodEditV2 {
    fun first(x: Int): Int = if (x > 0) x + 1 else x - 1

    fun second(x: Int): String = if (x > 0) "pos" else "non-pos"
}
