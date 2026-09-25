package com.example.target

/** Calls a multi-file function, which Kotlin compiles to a call on the facade `MultifileText`. */
fun callsMultifileGreeting(): String = multifileGreeting("x")

/** Omits a multi-file function's default, so it calls the facade's `multifileFarewell$default`. */
fun callsMultifileFarewellWithDefault(): String = multifileFarewell("x")

/** Reads a multi-file property through the facade's getter. */
fun readsMultifileSuffix(): String = multifileSuffix

/**
 * Calls a top-level function of another file. This file facade's functions are ordinary code, so
 * none is marked `MULTIFILE_FACADE`, though this one only calls another class.
 */
fun callsAnotherFile(): Int = callsIntoOtherFacade()

class KindClass {
    fun f(): Int = 1
}

object KindObject {
    fun g(): Int = 2
}
