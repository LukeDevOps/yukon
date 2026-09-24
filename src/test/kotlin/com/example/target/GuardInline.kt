package com.example.target

/** An in-scope inline function with no branch of its own, so a copy of it adds lines to whatever outcome guards the call. */
inline fun guardInlineHelper(value: Int): Int {
    val doubled = value * 2 // marker: guardInlineHelper-body
    return doubled + 1 // marker: guardInlineHelper-return
}
