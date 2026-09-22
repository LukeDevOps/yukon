package com.example.target.keypairs

/** `check` takes one parameter. */
class DescriptorChangedV1 {
    fun check(x: Int): String = if (x > 0) "pos" else "non-pos"
}

/** The same method name and body, with an unused second parameter added, changing the descriptor. */
class DescriptorChangedV2 {
    fun check(
        x: Int,
        @Suppress("UNUSED_PARAMETER") extra: Int,
    ): String = if (x > 0) "pos" else "non-pos"
}
