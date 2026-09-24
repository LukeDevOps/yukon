package com.example.target

import java.io.File

/** Fixtures for the condition writer's Kotlin idioms. Each method holds one site. See ADR 0037. */
class ConditionTarget(
    val limit: Int,
) {
    fun areEqualCheck(
        a: String?,
        b: String?,
    ): Int {
        if (a == b) return 1
        return 0
    }

    fun notEqualCheck(
        a: String?,
        b: String?,
    ): Int {
        if (a != b) return 1
        return 0
    }

    fun propertyCheck(other: ConditionTarget): Int {
        if (other.limit > 3) return 1
        return 0
    }

    fun javaGetterCheck(file: File): Int {
        if (file.name == "x") return 1
        return 0
    }

    fun javaIsGetterCheck(file: File): Int {
        if (file.isFile) return 1
        return 0
    }

    fun compareToCheck(
        a: Int,
        b: Int,
    ): Int {
        if (a.compareTo(b) > 0) return 1
        return 0
    }

    fun templateCheck(
        name: String,
        count: Int,
    ): Int {
        if ("$name-$count" == "x-1") return 1
        return 0
    }

    fun inlineCaller(amount: Int): Int = conditionInlineHelper(amount)

    fun companionCheck(): Int {
        if (ConditionHolder.threshold > 5) return 1
        return 0
    }

    fun objectCheck(): Int {
        if (ConditionSettings.enabled) return 1
        return 0
    }

    fun booleanCheck(flag: Boolean): Int {
        if (flag) return 1
        return 0
    }

    fun negatedBooleanCheck(flag: Boolean): Int {
        if (!flag) return 1
        return 0
    }

    fun intZeroCheck(count: Int): Int {
        if (count != 0) return 1
        return 0
    }

    fun isCheck(value: Any): Int {
        if (value is String) return 1
        return 0
    }

    fun notIsCheck(value: Any): Int {
        if (value !is String) return 1
        return 0
    }

    fun identityCheck(
        a: Any,
        b: Any,
    ): Int {
        if (a === b) return 1
        return 0
    }

    fun enumCheck(mode: ConditionMode): Int {
        if (mode == ConditionMode.FAST) return 1
        return 0
    }

    fun literalCheck(text: String): Int {
        if (text == "say \"hi\"\nbye") return 1
        return 0
    }

    fun doubleCheck(discounted: Double): Int {
        if (discounted > 100.0) return 1
        return 0
    }
}

/** An extension function. kotlinc names its receiver `$this$extensionCheck` and reads [ConditionTarget.limit] through its getter. */
fun ConditionTarget.extensionCheck(): Int {
    if (limit > 2) return 1
    return 0
}

/** Reads [ConditionTarget.limit] from outside its class, so kotlinc calls the getter. */
fun getterCheck(target: ConditionTarget): Int {
    if (target.limit > 3) return 1
    return 0
}

/** Narrows a `Long` to an `Int`, so kotlinc emits `l2i`. */
fun narrowingCheck(total: Long): Int {
    if (total.toInt() > 3) return 1
    return 0
}

/** Narrows an `Int` to a `Byte`, so kotlinc emits `i2b`. */
fun byteCheck(code: Int): Int {
    if (code.toByte() > 3) return 1
    return 0
}

/** Compares an `Int` with a `Long`, so kotlinc widens the `Int` with `i2l`. */
fun wideningCheck(
    count: Int,
    limit: Long,
): Int {
    if (count < limit) return 1
    return 0
}

/** `===` between two instances of an ordinary class. */
fun sameInstanceCheck(
    a: ConditionTarget,
    b: ConditionTarget,
): Int {
    if (a === b) return 1
    return 0
}

/** An in-scope inline function whose one site is copied into [ConditionTarget.inlineCaller]. */
inline fun conditionInlineHelper(value: Int): Int {
    if (value > 10) return 1
    return 0
}

/** Holds a companion property read from [ConditionTarget.companionCheck]. */
class ConditionHolder {
    companion object {
        val threshold: Int = System.getProperty("condition.threshold")?.length ?: 7
    }
}

/** An `object` whose property [ConditionTarget.objectCheck] reads. */
object ConditionSettings {
    val enabled: Boolean = System.getProperty("condition.enabled") != null
}

/** An enum compared with `==` in [ConditionTarget.enumCheck]. */
enum class ConditionMode { FAST, SLOW }
