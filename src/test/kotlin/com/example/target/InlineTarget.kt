package com.example.target

inline fun topLevelInline(a: Int): Int = a + 1

fun topLevelPlain(a: Int): Int = a - 1

class InlineTarget {
    inline fun member(
        a: Int,
        b: Int = 1,
    ): Int = a + b

    fun plain(a: Int): Int = a * 2

    fun same(a: Int): Int = same(a, 1)

    inline fun same(
        a: Int,
        b: Int,
    ): Int = a + b
}
