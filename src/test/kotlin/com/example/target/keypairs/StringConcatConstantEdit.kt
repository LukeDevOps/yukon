package com.example.target.keypairs

/** The condition builds a string with a constant prefix. */
class StringConcatConstantEditV1 {
    fun check(id: Int): Int = if (("user:" + id).length > 7) 1 else 0
}

/** The same condition with a different constant prefix: a different condition. */
class StringConcatConstantEditV2 {
    fun check(id: Int): Int = if (("account:" + id).length > 7) 1 else 0
}
