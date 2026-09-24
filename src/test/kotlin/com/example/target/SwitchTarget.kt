package com.example.target

/** The enum the Kotlin switch fixtures switch over. See ADR 0038. */
enum class Tint { RED, GREEN, BLUE }

/** Each kotlinc lowering of a `when` over an enum and a string, for ADR 0038. */
class SwitchTarget {
    fun enumWithElse(tint: Tint): Int =
        when (tint) {
            Tint.RED -> 1
            Tint.BLUE -> 3
            else -> 0
        }

    fun enumStatement(tint: Tint): Int {
        var result = 0
        when (tint) {
            Tint.GREEN -> result = 2
            Tint.RED -> result = 1
            Tint.BLUE -> result = 3
        }
        return result
    }

    fun enumExhaustive(tint: Tint): Int =
        when (tint) {
            Tint.RED -> 1
            Tint.GREEN -> 2
            Tint.BLUE -> 3
        }

    fun enumNullable(tint: Tint?): Int =
        when (tint) {
            Tint.RED -> 1
            Tint.GREEN -> 2
            else -> 0
        }

    fun stringWhen(status: String): Int =
        when (status) {
            "open" -> 1
            "closed", "done" -> 2
            "Aa" -> 3
            "BB" -> 4
            else -> 0
        }

    fun stringSingle(status: String): Int =
        when (status) {
            "open" -> 1
            else -> 0
        }

    fun stringTwo(status: String): Int =
        when (status) {
            "open" -> 1
            "closed" -> 2
            else -> 0
        }

    fun stringNullable(status: String?): Int =
        when (status) {
            "open" -> 1
            "closed" -> 2
            "done" -> 3
            else -> 0
        }

    fun javaEnum(color: SwitchColor): Int =
        when (color) {
            SwitchColor.GREEN -> 2
            SwitchColor.RED -> 1
            else -> 0
        }

    fun enumWithNullBranch(tint: Tint?): Int =
        when (tint) {
            null -> -1
            Tint.RED -> 1
            else -> 0
        }
}
