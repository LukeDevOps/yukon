package com.example.target

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine

/**
 * A suspension point that never actually suspends: [suspendCoroutineUninterceptedOrReturn]'s
 * block returns a value directly, so the caller's state machine never sees `COROUTINE_SUSPENDED`.
 */
suspend fun pauseNow(): Int = suspendCoroutineUninterceptedOrReturn { 1 }

/** The continuation [pauseLater] stashes, so a test can resume it from outside the coroutine. */
private var savedContinuation: Continuation<Int>? = null

/** A suspension point that really suspends, storing its continuation in [savedContinuation]. */
suspend fun pauseLater(): Int =
    suspendCoroutineUninterceptedOrReturn { continuation ->
        savedContinuation = continuation
        COROUTINE_SUSPENDED
    }

/** Resumes whatever [pauseLater] call is currently suspended, with [value] as its result. */
fun resumePauseLater(value: Int) {
    val continuation = savedContinuation
    savedContinuation = null
    continuation?.resume(value)
}

/** A top-level suspend function with two suspension points and one adopter conditional. */
suspend fun twoPoints(x: Int): Int {
    val a = pauseNow()
    val r = if (x > 0) a + x else a - x // marker: twoPoints-if
    val b = pauseNow()
    return r + b
}

/** Same shape as [twoPoints], but its suspension points really suspend. */
suspend fun twoPointsSuspending(x: Int): Int {
    val a = pauseLater()
    val r = if (x > 0) a + x else a - x // marker: twoPointsSuspending-if
    val b = pauseLater()
    return r + b
}

/** A suspend function with no suspension point at all: no coroutine state machine is generated for it. */
suspend fun noPoint(x: Int): Int = if (x > 0) x else -x // marker: noPoint-if

class Holder {
    /** A member suspend function with one suspension point and one adopter conditional. */
    suspend fun member(x: Int): Int {
        val a = pauseNow()
        return if (x > a) x else a // marker: member-if
    }
}

/** Starts [block] with [startCoroutine], ignoring its result: this fixture only needs the state machine to run. */
private fun <T> runSuspend(block: suspend () -> T) {
    block.startCoroutine(
        object : Continuation<T> {
            override val context: CoroutineContext = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {}
        },
    )
}

fun runTwoPoints(x: Int) = runSuspend { twoPoints(x) }

fun runTwoPointsSuspending(x: Int) = runSuspend { twoPointsSuspending(x) }

fun runNoPoint(x: Int) = runSuspend { noPoint(x) }

fun runMember(x: Int) = runSuspend { Holder().member(x) }

/** Builds a suspend lambda with one suspension point and one adopter conditional, and starts it. */
fun runLambda(x: Int) =
    runSuspend {
        val a = pauseNow()
        if (x > 0) a + x else a - x // marker: lambda-if
    }

/** A field named `label`, of type `Int`, on an ordinary non-suspend class: not a continuation. */
class LabelHolder(
    @JvmField val label: Int,
)

/**
 * A plain, non-suspend function whose own switch is preceded by a `GETFIELD` of a field named
 * `label`, the same shape shape (i) matches inside a suspend-shaped method. Not suspend-shaped, so
 * the coroutine recogniser must never run against it at all.
 */
fun plainSwitchOnLabel(holder: LabelHolder): String =
    when (holder.label) { // marker: plainSwitchOnLabel-switch
        0 -> "zero"

        1 -> "one"

        2 -> "two"

        else -> "other"
    }

/**
 * A plain, non-suspend function comparing two object references, the same instruction shape (ii)
 * matches. Not suspend-shaped, so it must never be mistaken for a suspended-marker compare.
 */
fun plainReferenceCompare(
    a: Any,
    b: Any,
): Boolean = a === b // marker: plainReferenceCompare-compare

/**
 * A suspend-shaped function whose own reference comparison, on its own locals, must not be
 * mistaken for the suspended-marker compare shape (ii) finds.
 */
suspend fun compareRefs(
    a: Any,
    b: Any,
): Boolean {
    pauseNow()
    return a === b // marker: compareRefs-compare
}

fun runPlainSwitchOnLabel(label: Int): String = plainSwitchOnLabel(LabelHolder(label))

fun runPlainReferenceCompare(
    a: Any,
    b: Any,
): Boolean = plainReferenceCompare(a, b)

fun runCompareRefs(
    a: Any,
    b: Any,
) = runSuspend { compareRefs(a, b) }
