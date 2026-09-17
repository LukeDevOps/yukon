package com.example.library

/**
 * An inline function declared outside `com.example.target`, used to prove that an inlined copy's
 * origin class is what decides whether the branch tier keeps or drops it, not which class the
 * copy physically sits in.
 */
inline fun libraryInline(value: Int): Int {
    if (value > 0) { // marker: libraryInline-if
        return value
    }
    return -value
}
