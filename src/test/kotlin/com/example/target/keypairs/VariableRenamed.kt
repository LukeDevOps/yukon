package com.example.target.keypairs

/** A condition on `x`. */
class VariableRenamedV1 {
    fun check(x: Int): String = if (x > 0) "pos" else "non-pos"
}

/** The same condition, with its parameter renamed from `x` to `value`. */
class VariableRenamedV2 {
    fun check(value: Int): String = if (value > 0) "pos" else "non-pos"
}
