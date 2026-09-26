package com.example.target

/**
 * A private two-`Int` constructor its companion calls, which kotlinc reaches through a synthetic
 * accessor `<init>(int, int, DefaultConstructorMarker)`, beside a public one-`Int` constructor.
 * The accessor has the descriptor a default-filling constructor for the one-`Int` constructor
 * would have, and tests no mask.
 */
class AccessorPair private constructor(
    val a: Int,
    val b: Int,
) {
    constructor(a: Int) : this(a, 0)

    companion object {
        fun make(): AccessorPair = AccessorPair(1, 2)
    }
}

/**
 * A private constructor its companion calls, with no other constructor the accessor
 * `<init>(String, DefaultConstructorMarker)` could be taken to fill defaults for.
 */
class AccessorName private constructor(
    val name: String,
) {
    companion object {
        fun of(name: String): AccessorName = AccessorName(name)
    }
}

/**
 * A private constructor with a default value: kotlinc gives it both an accessor
 * `<init>(String, int, DefaultConstructorMarker)` and a default-filling constructor
 * `<init>(String, int, int, DefaultConstructorMarker)`, which calls the private constructor
 * directly.
 */
class AccessorDefaults private constructor(
    val name: String,
    val size: Int = 1,
) {
    companion object {
        fun full(): AccessorDefaults = AccessorDefaults("a", 2)

        fun omitting(): AccessorDefaults = AccessorDefaults("a")
    }
}

/** A sealed class, whose private constructor its subclass reaches through the accessor. */
sealed class AccessorShape {
    class Circle : AccessorShape()
}

/**
 * A default value that constructs this class through a constructor whose descriptor is the
 * default-filling constructor's own less its marker, so a rule reading any one call in its body
 * would take it for an accessor.
 */
class SelfDefault(
    val v: Int,
    val next: SelfDefault? = SelfDefault(0, null, 0),
) {
    constructor(v: Int, next: SelfDefault?, extra: Int) : this(v, next)
}

/** Calls [SelfDefault] leaving out `next`, from another class. */
object SelfDefaultUser {
    fun make(): SelfDefault = SelfDefault(1)
}
