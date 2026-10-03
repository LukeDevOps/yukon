package com.example.target

import java.io.Closeable

/** Branch outcomes of each routine shape, and of the shapes that stay findings. */
class RoutineTarget {
    private lateinit var name: String

    fun load(): String = "loaded"

    fun log(message: String) {
        println(message)
    }

    fun safeCall(value: String?): Int? = value?.length

    fun elvisConstant(value: String?): String = value ?: "none"

    /** A finally body reading a local of its own, which sits in a different slot in each copy. */
    fun finallyWithLocal(
        resource: java.io.Closeable?,
        body: () -> Unit,
    ) {
        try {
            body()
        } finally {
            val r = resource
            if (r != null) r.close()
        }
    }

    /** A finally body with a loop of its own. */
    fun finallyWithLoop(
        xs: IntArray,
        body: () -> Unit,
    ): Int {
        var count = xs.size
        try {
            body()
        } finally {
            for (i in 0 until count) if (xs[i] > 3) count--
        }
        return count
    }

    /** A try expression whose catch recovers with a value. */
    fun caughtGuardExpression(
        value: String?,
        retryable: Boolean,
    ): Int =
        try {
            if (value == null) throw IllegalArgumentException("missing")
            value.length
        } catch (e: IllegalArgumentException) {
            if (retryable) -1 else throw e
        }

    /** The try block never completes normally, so kotlinc emits the finally body only on the exception path. */
    fun loopForeverFinally(
        queue: java.util.concurrent.BlockingQueue<String>,
        running: Boolean,
    ) {
        try {
            while (true) queue.take()
        } finally {
            if (running) log("stopped")
        }
    }

    /** The null side counts, which is the adopter's own logic, not a default. */
    fun countMissing(values: Array<String?>): Int {
        var missing = 0
        for (value in values) {
            if (value == null) missing++
        }
        return missing
    }

    fun elvisReturn(value: String?): Int {
        val text = value ?: return 0
        return text.length
    }

    fun elvisCall(value: String?): String = value ?: load()

    fun elvisThrow(value: String?): String = value ?: throw IllegalStateException("missing value")

    fun elvisError(value: String?): String = value ?: error("missing value for $name")

    fun lateinitName(): String = name

    fun sealedWhen(shape: RoutineShape): Int =
        when (shape) {
            is RoutineCircle -> 1
            is RoutineSquare -> 2
        }

    fun instanceWhen(value: Any): Int =
        when (value) {
            is String -> 1
            is Int -> 2
            else -> throw IllegalArgumentException("unknown $value")
        }

    fun guardThrow(amount: Int): Int {
        if (amount < 0) throw IllegalArgumentException("negative amount: $amount")
        return amount
    }

    fun guardLogThenThrow(amount: Int): Int {
        if (amount < 0) {
            log("negative amount")
            throw IllegalArgumentException("negative amount: $amount")
        }
        return amount
    }

    fun capTotal(discounted: Double): Double {
        var total = discounted
        if (discounted > 100.0) total = 100.0
        return total
    }

    fun tryFinally(flag: Boolean): Int {
        var result = 0
        try {
            result = load().length
        } finally {
            if (flag) result += 1
        }
        return result
    }

    fun useBlock(
        resource: Closeable,
        flag: Boolean,
    ): Int = resource.use { if (flag) 1 else 2 }

    fun typedCatch(flag: Boolean): Int =
        try {
            load().length
        } catch (e: IllegalStateException) {
            if (flag) 1 else 2
        }
}

sealed class RoutineShape

class RoutineCircle : RoutineShape()

class RoutineSquare : RoutineShape()
