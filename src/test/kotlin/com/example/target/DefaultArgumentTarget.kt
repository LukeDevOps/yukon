package com.example.target

/** A final class whose `f` has three optional parameters, exercising a multi-bit mask. */
class DefaultArgumentTarget {
    fun f(
        a: Int,
        b: Int = 1,
        c: String = "x",
        d: Long = 2L,
    ): Int = a + b + c.length + d.toInt()
}

fun topLevelWithDefault(
    a: Int,
    b: Int = 5,
): Int = a + b

fun String.extWithDefault(n: Int = 3): Int = length + n

class ConstructedWithDefault(
    val a: Int,
    val b: Int = 7,
)

open class OpenBase {
    open fun greet(name: String = "world"): String = "hello $name"
}

class OpenSub : OpenBase() {
    override fun greet(name: String): String = "hi $name"
}

interface Greeter {
    fun greet(name: String = "there"): String
}

class GreeterImpl : Greeter {
    override fun greet(name: String): String = "yo $name"
}

class OverloadsTarget {
    @JvmOverloads
    fun withOverloads(
        a: Int,
        b: Int = 1,
    ): Int = a + b
}

data class DataTarget(
    val a: Int,
    val b: Int = 1,
)

inline fun inlineWithDefault(
    a: Int,
    b: Int = 1,
): Int = a + b

/**
 * A member extension function with a default: the receiver is the target's first JVM parameter
 * (slot 1, named `$this$decorate` in the LocalVariableTable), but the compiler's mask counts value
 * parameters only, so bit 0 is `suffix`, not the receiver.
 */
class MemberExtensionTarget {
    fun String.decorate(suffix: String = "!"): String = this + suffix

    fun call(): String = "hi".decorate()
}

/**
 * Sixteen optional parameters: bit 15's mask constant, 32768, no longer fits `SIPUSH`, so kotlinc
 * emits that test with `LDC`, the third of the three constant shapes the mask scan recognises.
 */
@Suppress("LongParameterList")
fun sixteenDefaults(
    p0: Int = 0,
    p1: Int = 0,
    p2: Int = 0,
    p3: Int = 0,
    p4: Int = 0,
    p5: Int = 0,
    p6: Int = 0,
    p7: Int = 0,
    p8: Int = 0,
    p9: Int = 0,
    p10: Int = 0,
    p11: Int = 0,
    p12: Int = 0,
    p13: Int = 0,
    p14: Int = 0,
    p15: Int = 0,
): Int = p0 + p1 + p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9 + p10 + p11 + p12 + p13 + p14 + p15

/**
 * `b`'s default expression itself does `a and 4`, on `a`, a real parameter rather than the
 * `$default` mask local. The analyser must not mistake this for a mask test just because it
 * shares the `ILOAD; ICONST_4; IAND` shape.
 */
fun withNonMaskAnd(
    a: Int,
    b: Int = if (a and 4 != 0) 10 else 20,
): Int = a + b

/**
 * Makes the real Kotlin calls this fixture's `$default` paths are woven for, so a test exercises
 * the woven bytecode from an actual call site rather than only reflectively invoking `$default`
 * directly.
 */
object Caller {
    fun callF(target: DefaultArgumentTarget) {
        target.f(1)
        target.f(1, 2)
        target.f(1, 2, "y")
        target.f(1, 2, "y", 3L)
    }

    fun callTopLevel() {
        topLevelWithDefault(1)
        topLevelWithDefault(1, 2)
    }

    fun callExtension() {
        "hi".extWithDefault()
        "hi".extWithDefault(5)
    }

    fun callConstructor() {
        ConstructedWithDefault(1)
        ConstructedWithDefault(1, 2)
    }

    fun callOpen(base: OpenBase) {
        base.greet()
    }

    fun callInterface(greeter: Greeter) {
        greeter.greet()
    }

    fun callOverloads(target: OverloadsTarget) {
        target.withOverloads(1)
    }

    fun callData() {
        DataTarget(1)
        DataTarget(1, 2).copy(a = 3)
    }

    fun callInline() {
        inlineWithDefault(1)
    }

    fun callNonMaskAnd() {
        withNonMaskAnd(1)
        withNonMaskAnd(1, 2)
    }
}
