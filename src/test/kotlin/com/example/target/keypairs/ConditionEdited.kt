package com.example.target.keypairs

/** `x > 0`. */
class ConditionEditedV1 {
    fun check(x: Int): String = if (x > 0) "pos" else "non-pos"
}

/** The same condition, edited from `x > 0` to `x >= 0`. */
class ConditionEditedV2 {
    fun check(x: Int): String = if (x >= 0) "pos" else "non-pos"
}
