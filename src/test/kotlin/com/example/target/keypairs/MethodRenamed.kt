package com.example.target.keypairs

/** A condition inside `check`. */
class MethodRenamedV1 {
    fun check(x: Int): String = if (x > 0) "pos" else "non-pos"
}

/** The same body, with the method renamed from `check` to `verify`. */
class MethodRenamedV2 {
    fun verify(x: Int): String = if (x > 0) "pos" else "non-pos"
}
