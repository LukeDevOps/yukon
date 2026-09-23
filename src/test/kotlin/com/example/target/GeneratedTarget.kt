package com.example.target

/**
 * A data class with one hand-written method, holding a conditional, beside its generated members.
 * See ADR 0026.
 */
data class GeneratedPoint(
    val x: Int,
    val y: String,
) {
    fun custom(): Int = if (x > 0) x else -x
}

/**
 * A data class whose `toString` is hand-written. kotlinc gives it a line-number table, so ADR 0026
 * leaves it unmarked while the generated `equals` and `hashCode` beside it are marked.
 */
data class GeneratedPointCustomToString(
    val x: Int,
    val y: String,
) {
    override fun toString(): String = "($x, $y)"
}

/**
 * A data class with a hand-written `equals`, holding a real conditional, and a hand-written
 * `hashCode`, while `toString`, `componentN` and `copy` are left to the compiler. ADR 0026 leaves
 * the two hand-written methods unmarked, since they carry line-number tables, and marks the rest.
 */
data class GeneratedPointCustomEquals(
    val x: Int,
    val y: String,
) {
    override fun equals(other: Any?): Boolean {
        if (other !is GeneratedPointCustomEquals) return false
        return x == other.x
    }

    override fun hashCode(): Int = x
}

/** A data class with a constructor default, so its `copy$default` carries an omission probe. */
data class GeneratedPointWithDefault(
    val x: Int,
    val y: Int = 0,
)

/**
 * Hand-writes `copy` and `component1` but declares none of `equals`, `hashCode`, or `toString`:
 * the all-three requirement leaves every method here [io.github.lukedevops.yukon.export.GeneratedBy.NONE].
 */
class HandWrittenCopy(
    val v: Int,
) {
    fun copy(v: Int = this.v): HandWrittenCopy = HandWrittenCopy(v)

    fun component1(): Int = v
}

enum class GeneratedColour { RED, GREEN }

/**
 * Compiled under the root build's default `-jvm-default` mode, so each method of its
 * `$DefaultImpls` class only forwards to the interface's own default method. The disable-mode
 * counterpart is `com.example.target.jvmdefaultdisable.DisabledDefaultInterface`.
 */
interface GeneratedInterface {
    fun withBody(): Int = 42

    val label: String
        get() = "enabled"
}

class GeneratedInterfaceImpl : GeneratedInterface

/** Shares a method name with [GeneratedInterface]'s default, but is not a `$DefaultImpls` class. */
class NotDefaultImpls {
    fun withBody(): Int = 7
}
