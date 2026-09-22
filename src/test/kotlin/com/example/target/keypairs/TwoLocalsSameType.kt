package com.example.target.keypairs

/**
 * Two conditions in one method, each on its own `Int` local. With a `LocalVariableTable`, `x` and
 * `y` fingerprint distinctly; stripped of one, both read as a bare `ILOAD` and collide.
 */
class TwoLocalsSameType {
    fun check(
        x: Int,
        y: Int,
    ): String {
        val first = if (x > 0) "pos" else "non-pos"
        val second = if (y > 0) "pos" else "non-pos"
        return first + second
    }
}
