package com.example.target

/**
 * A private method and a private field, reached only through the synthetic `access$` accessors
 * kotlinc generates for a nested class: JVM privacy is per class file, not per Kotlin
 * declaration, so `Inner` cannot call `secret()` or read `x` directly even though the source
 * allows it. A private companion member reached from the enclosing class needs the same kind of
 * accessor, generated on the companion's own class instead.
 */
class AccessorTarget {
    private val x: Int = 3

    private fun secret(): Int = 9

    inner class Inner {
        fun callSecret(): Int = secret()

        fun readX(): Int = x
    }

    companion object {
        private fun companionSecret(): Int = 11
    }

    fun callCompanionSecret(): Int = companionSecret()
}
