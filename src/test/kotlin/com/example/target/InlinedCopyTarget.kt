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

/** A pair of sort keys for [sortByBoth]. */
class SortKeys(
    val first: Int,
    val second: Int,
)

/**
 * Sorts with `thenBy`, an inline function returning an object. kotlinc regenerates that object as
 * a class of this file, `InlinedCopyTargetKt$sortByBoth$$inlined$thenBy$1`, whose code is the
 * standard library's, including its `if (previousCompare != 0)`.
 */
fun sortByBoth(items: List<SortKeys>): List<SortKeys> = items.sortedWith(compareBy<SortKeys> { it.first }.thenBy { it.second })

/**
 * Its own `for` loop next to an inlined `filter`, whose loop over the list kotlinc copies in from
 * the standard library. Both loops test `Iterator.hasNext` on an unnamed local.
 */
fun ownLoopBesideFilter(values: List<Int>): Int {
    var sum = 0
    for (value in values) { // marker: ownLoopBesideFilter-for
        sum += value
    }
    return sum + values.filter { it > 0 }.size
}
