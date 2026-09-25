package com.example.target

/**
 * Two defaults on the function's own line. kotlinc writes no new line-number entry for either
 * fill block, so both omission probes read this line. See ADR 0044.
 */
@Suppress("ktlint:standard:function-signature")
fun sameLine(a: Int = 1, b: Int = 2): Int = a + b

/** A default whose expression sits on the line after its parameter's name. See ADR 0044. */
fun wrappedDefault(
    a: Int,
    label: String =
        "wrapped",
): String = label + a
