package com.example.target.keypairs

/** `a > 0 && b > 0`, two tracked sites, one per operand. */
class AndConditionSecondOperandEditedV1 {
    fun check(
        a: Int,
        b: Int,
    ): String = if (a > 0 && b > 0) "both" else "not-both"
}

/** The first operand, `a > 0`, is unchanged; the second is edited from `b > 0` to `b >= 0`. */
class AndConditionSecondOperandEditedV2 {
    fun check(
        a: Int,
        b: Int,
    ): String = if (a > 0 && b >= 0) "both" else "not-both"
}
