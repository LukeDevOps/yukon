package com.example.target

/** A data class with one hand-written method beside its generated members. See ADR 0026. */
data class GeneratedPoint(
    val x: Int,
    val y: String,
) {
    fun custom(): Int = x
}

/** A data class whose `toString` is hand-written; ADR 0026 marks it generated anyway. */
data class GeneratedPointCustomToString(
    val x: Int,
    val y: String,
) {
    override fun toString(): String = "($x, $y)"
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

interface GeneratedInterface {
    fun withBody(): Int = 42
}

class GeneratedInterfaceImpl : GeneratedInterface

/** Shares a method name with [GeneratedInterface]'s default, but is not a `$DefaultImpls` class. */
class NotDefaultImpls {
    fun withBody(): Int = 7
}
