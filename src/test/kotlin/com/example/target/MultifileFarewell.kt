@file:JvmMultifileClass
@file:JvmName("MultifileText")

package com.example.target

/**
 * The second file of `MultifileText`. Its default argument gives the facade a synthetic
 * `multifileFarewell$default` that forwards to the part's own, and its property gives the facade a
 * getter forwarder. See ADR 0041.
 */
fun multifileFarewell(
    name: String,
    times: Int = 1,
): String = "bye $name".repeat(times)

val multifileSuffix: String = "!"
