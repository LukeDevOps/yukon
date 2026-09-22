package com.example.target.keypairs

/** A `when` over an `Int` with two cases and an `else`. */
class SwitchCaseAddedKotlinV1 {
    fun classify(x: Int): String =
        when (x) {
            1 -> "one"
            2 -> "two"
            else -> "other"
        }
}

/** The same `when`, with a third case added between the existing ones and `else`. */
class SwitchCaseAddedKotlinV2 {
    fun classify(x: Int): String =
        when (x) {
            1 -> "one"
            2 -> "two"
            3 -> "three"
            else -> "other"
        }
}
