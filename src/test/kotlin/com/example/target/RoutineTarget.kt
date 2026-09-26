package com.example.target

import java.io.Closeable

/** Branch outcomes of each shape ADR 0046 names, and of the shapes it leaves as findings. */
class RoutineTarget {
    private lateinit var name: String

    fun load(): String = "loaded"

    fun log(message: String) {
        println(message)
    }

    fun safeCall(value: String?): Int? = value?.length

    fun elvisConstant(value: String?): String = value ?: "none"

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
