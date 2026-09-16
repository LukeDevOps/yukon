package com.example.target

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * A bound reference to a private method, held in a function-typed value so it is not inlined.
 * kotlinc compiles `::secret` to its own class extending `FunctionReferenceImpl`, a body class
 * under ADR 0024: without the body-class rule, both [secret] and the reference class's own
 * `invoke` would look uncalled, since [viaReference] never names either directly.
 */
class FunctionReferenceTarget {
    private fun secret(): Int = 1

    fun viaReference(): Int {
        val f: () -> Int = ::secret
        return f()
    }
}

/**
 * A suspend lambda passed to a non-inline function, using only the standard library's own
 * [kotlin.coroutines.startCoroutine] and [kotlin.coroutines.Continuation] so the fixture needs no
 * coroutines dependency. kotlinc compiles the lambda to its own class extending `SuspendLambda`, a
 * second body-class shape reached by `new` rather than `getstatic`.
 */
class SuspendLambdaTarget {
    private fun helper(): Int = 2

    fun runIt(block: suspend () -> Unit) = block.startCoroutine(Continuation(EmptyCoroutineContext) { })

    fun usesSuspend() = runIt { helper() }
}

/** An object expression: a third body-class shape, also reached by `new`. */
class ObjectExpressionTarget {
    private fun helper(): Int = 3

    fun makeHandler(): Runnable =
        object : Runnable {
            override fun run() {
                helper()
            }
        }
}

/** A named, non-local nested class. It carries no `EnclosingMethod` attribute, so creating it with `new` is not expanded. */
class NamedNestedTarget {
    class Nested {
        fun method(): Int = 4
    }
}

fun makesNamedNested(): NamedNestedTarget.Nested = NamedNestedTarget.Nested()

/**
 * A named Kotlin object. Reading it compiles to `getstatic INSTANCE`, the same instruction shape
 * chunk one's field-use rule already turns into a `<clinit>` candidate, but it carries no
 * `EnclosingMethod` attribute, so the body-class rule must not expand it.
 */
object NamedObjectTarget {
    fun method(): Int = 5
}

fun readsNamedObjectTarget(): NamedObjectTarget = NamedObjectTarget
