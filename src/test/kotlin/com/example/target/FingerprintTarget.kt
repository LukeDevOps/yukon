package com.example.target

/** A structurally identical condition to [conditionOnX], with its parameter renamed. */
fun conditionOnY(y: Int): Boolean = y > 0

/** A single conditional jump on a parameter, used to check the fingerprint's exact text. */
fun conditionOnX(x: Int): Boolean = x > 0

/** Same condition as [withoutExtraLocal], with an unrelated local declared just before it. */
fun withExtraLocal(x: Int): Boolean {
    val unused = 42
    return x > 0
}

fun withoutExtraLocal(x: Int): Boolean = x > 0

/** Two conditional jumps, one per operand, so `a` and `b` each get their own fingerprint. */
fun andCondition(
    a: Boolean,
    b: Boolean,
): Boolean {
    if (a && b) return true
    return false
}

/** A ternary inside the condition: a merge point sits inside the fingerprint window. */
fun ternaryCondition(
    x: Int,
    y: Int,
): Boolean {
    val bigger = if (x > y) x else y
    return bigger > 0
}

/**
 * An `if`/`else` whose true branch ends with an unconditional jump over the false branch,
 * followed immediately by another condition. The next site's window starts right after that
 * `GOTO`.
 */
fun conditionAfterJump(
    a: Boolean,
    x: Int,
): Boolean {
    if (a) {
        sinkOnly(true)
    } else {
        sinkOnly(false)
    }
    return x > 0
}

/** A loop head reached by fall-through first, then by a backward branch at the loop's end. */
fun conditionAtLoopHead(n: Int): Int {
    var i = 0
    var total = 0
    while (i < n) {
        if (i % 2 == 0) total += i
        i++
    }
    return total
}

/** A condition inside a `catch` block, so its window starts at the handler's own depth of one. */
fun conditionInCatch(x: Int): Int =
    try {
        risky(x)
    } catch (e: RuntimeException) {
        if (x > 0) 1 else -1
    }

private fun sinkOnly(a: Boolean) {
    if (a) return
}

private fun risky(x: Int): Int {
    if (x == 0) throw RuntimeException("zero")
    return x
}

/** A non-inline higher-order function, so a lambda passed to it goes through `invokedynamic`. */
fun apply(
    f: (Int) -> Boolean,
    v: Int,
): Boolean = f(v)

/** A lambda created inside the condition itself, structurally identical to [lambdaConditionTwo]. */
fun lambdaConditionOne(x: Int): Boolean {
    if (apply({ v -> v > 0 }, x)) return true
    return false
}

/**
 * Structurally identical to [lambdaConditionOne]. kotlinc numbers this method's lambda body
 * differently since it is declared later in the file; the fingerprint must not depend on that
 * number.
 */
fun lambdaConditionTwo(x: Int): Boolean {
    if (apply({ v -> v > 0 }, x)) return true
    return false
}
