package com.example.target

/** Methods whose branch outcomes guard known lines and calls, for ADR 0037's guarded code and guards. */
class GuardTarget {
    fun sink(value: Int): Int = value + 1

    fun before(value: Int): Int = value - 1

    fun after(value: Int): Int = value * 3

    fun shared(value: Int): Int = value

    fun risky(value: Int): Int = if (value > 100) throw IllegalStateException("too big") else value

    fun recover(value: Int): Int = -value

    fun ifElse(flag: Boolean): Int {
        val start = 1
        val result =
            if (flag) {
                sink(start) // marker: ifElse-then
            } else {
                sink(start + 1) // marker: ifElse-else
            }
        return after(result) // marker: ifElse-after
    }

    fun blankInArm(flag: Boolean): Int {
        var total = 0
        if (flag) {
            total += sink(1) // marker: blankInArm-first

            // A comment and a blank line sit inside this arm.
            total += sink(2) // marker: blankInArm-last
        }
        return total
    }

    fun ifOnly(flag: Boolean): Int {
        var total = 0
        if (flag) {
            total = sink(total) // marker: ifOnly-then
        }
        return total // marker: ifOnly-after
    }

    fun elvis(value: String?): Int {
        val text = value ?: return 0 // marker: elvis
        return text.length // marker: elvis-after
    }

    fun nested(
        outer: Boolean,
        inner: Boolean,
    ): Int {
        var total = 0
        if (outer) {
            total += 1 // marker: nested-outer-then
            if (inner) {
                total += sink(total) // marker: nested-inner-then
            }
        }
        return total
    }

    fun beforeAndAfter(flag: Boolean): Int {
        val first = before(1)
        val middle = if (flag) 2 else 3
        return after(first + middle)
    }

    fun bothArms(flag: Boolean): Int =
        if (flag) {
            shared(1)
        } else {
            shared(2) + shared(3)
        }

    fun catchAcrossBranch(flag: Boolean): Int =
        try {
            val first = before(1)
            if (flag) risky(first) else first
        } catch (e: IllegalStateException) {
            recover(0)
        }

    fun catchInsideArm(flag: Boolean): Int {
        if (flag) {
            try {
                return risky(1)
            } catch (e: IllegalStateException) {
                return recover(1)
            }
        }
        return 0
    }

    fun loop(limit: Int): Int {
        var index = 0
        var total = 0
        while (index < limit) {
            total += sink(index) // marker: loop-body
            index++ // marker: loop-step
        }
        return total
    }

    fun inlineInArm(flag: Boolean): Int {
        if (flag) {
            return guardInlineHelper(2) // marker: inlineInArm-then
        }
        return 0
    }

    fun stdlibInArm(
        flag: Boolean,
        values: List<Int>,
    ): Int {
        if (flag) {
            return values.map { it + 1 }.firstOrNull { it > 2 } ?: 0 // marker: stdlibInArm-then
        }
        return 0
    }

    fun lambdaInArm(flag: Boolean): () -> Int {
        if (flag) {
            return { sink(1) }
        }
        return { 0 }
    }

    fun objectInArm(flag: Boolean): Runnable? {
        if (flag) {
            return object : Runnable {
                override fun run() {
                    sink(1)
                }
            }
        }
        return null
    }

    fun withDefault(value: Int = 7): Int = value + 2

    fun defaultInArm(flag: Boolean): Int {
        if (flag) {
            return withDefault()
        }
        return 0
    }

    fun staticInArm(flag: Boolean): Int {
        if (flag) {
            return GuardConfig.limit
        }
        return 0
    }
}

/** An in-scope class used only through a static field, so a read of it is an edge to its initializer. */
object GuardConfig {
    @JvmField
    val limit: Int = System.getProperty("guard.limit")?.toIntOrNull() ?: 5
}

/** A plain call a test can find after a suspension point. */
fun afterSuspension(value: Int): Int = value + 1

/** A suspension point inside an `if` arm, with a call after it. */
suspend fun suspendInArm(value: Int): Int {
    if (value > 0) {
        val paused = pauseNow() // marker: suspendInArm-call
        return afterSuspension(paused + value) // marker: suspendInArm-after
    }
    return -value
}
