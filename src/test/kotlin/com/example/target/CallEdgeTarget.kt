package com.example.target

import com.example.other.OtherTarget

/** A plain, in-scope static function, called from [CallEdgeTarget] to prove a static in-scope call edge. */
fun staticHelper(): Int = 42

/** A simple in-scope interface, implemented elsewhere, so a call through it is genuinely virtual. */
interface Classifier {
    fun classify(value: Int): Int
}

class ClassifierImpl : Classifier {
    override fun classify(value: Int): Int = value
}

/**
 * A non-inline higher-order function. The lambda [CallEdgeTarget.callsLambda] passes here compiles
 * to a real synthetic body on [CallEdgeTarget] itself, reached through the `invokedynamic` at the
 * call site, not through this function.
 */
fun applyOp(
    op: (Int) -> Int,
    value: Int,
): Int = op(value)

/**
 * Exercises every call-edge shape [io.github.lukedevops.yukon.instrumentation.branch.BranchSiteAnalyzer]
 * records: a private same-class call, another in-scope class's constructor and method, a static
 * in-scope function, a JDK call and a Kotlin stdlib call (both out of scope), an interface call, a
 * dropped self-edge, a cross-class `$default` pass-through, and a lambda passed to a non-inline
 * function.
 */
class CallEdgeTarget {
    private fun privateHelper(): Int = 7

    fun callsPrivateMethod(): Int = privateHelper()

    fun callsOtherClass(): Int {
        val other = OtherTarget()
        other.doSomething()
        return 1
    }

    fun callsStaticFunction(): Int = staticHelper()

    fun callsJdkMethod(): Long = System.currentTimeMillis()

    fun callsKotlinStdlib(): String = listOf(1, 2, 3).joinToString()

    fun callsInterfaceMethod(c: Classifier): Int = c.classify(1)

    fun callsSelfRecursively(n: Int): Int = if (n <= 0) 0 else callsSelfRecursively(n - 1)

    fun callsWithDefaultArgument(x: DefaultArgumentTarget): Int = x.f(1)

    fun callsLambda(): Int = applyOp({ it + 1 }, 5)
}

/** Two non-probed methods calling each other, used to prove pass-through resolution terminates on a cycle. */
class CycleTarget {
    fun real(): Int = a()

    fun a(): Int = b()

    fun b(): Int = a()
}

/** A template method: `run` calls an abstract `step` on its own class, which has no probe and no body. */
abstract class TemplateTarget {
    fun run(): Int = step()

    abstract fun step(): Int
}

open class BaseTarget {
    fun inherited(): Int = 1
}

/** Calls a method it only inherits, so the callee is not in this class's own method table. */
class SubTarget : BaseTarget() {
    fun go(): Int = inherited()
}
