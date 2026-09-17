package com.example.target

import com.example.library.libraryInline

/**
 * Calls `map`, whose own body brings in branch sites from another file, and `firstOrNull`, whose
 * predicate carries a conditional the adopter wrote directly at this call site.
 */
fun useCollections(values: List<Int>): Int? {
    val doubled = values.map { it * 2 } // marker: useCollections-map
    return doubled.firstOrNull { if (it > 10) true else false } // marker: useCollections-predicate
}

/** A private inline function with a conditional, copied into [callTakingTrueBranch] and [callTakingFalseBranch]. */
private inline fun sameFileInline(flag: Boolean): Int {
    if (flag) { // marker: sameFileInline-if
        return 1
    }
    return 0
}

fun callTakingTrueBranch(n: Int): Int = sameFileInline(n > 0) // marker: callTakingTrueBranch

fun callTakingFalseBranch(n: Int): Int = sameFileInline(n <= 0) // marker: callTakingFalseBranch

/** Calls an inline function declared in another package, so its copy's origin is out of scope unless that package is included too. */
fun useLibraryInline(value: Int): Int = libraryInline(value) // marker: useLibraryInline
