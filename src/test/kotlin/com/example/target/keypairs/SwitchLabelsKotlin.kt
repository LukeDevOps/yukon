package com.example.target.keypairs

import com.example.target.Tint

/** A `when` over an enum, before a case is added to it. See ADR 0038. */
class SwitchLabelsKotlinV1 {
    fun enumWhen(tint: Tint): Int =
        when (tint) {
            Tint.RED -> 1
            Tint.BLUE -> 3
            else -> 0
        }
}

/**
 * [SwitchLabelsKotlinV1] with a case added first, which renumbers every value in kotlinc's
 * `$WhenMappings` array.
 */
class SwitchLabelsKotlinV2 {
    fun enumWhen(tint: Tint): Int =
        when (tint) {
            Tint.GREEN -> 2
            Tint.RED -> 1
            Tint.BLUE -> 3
            else -> 0
        }
}
