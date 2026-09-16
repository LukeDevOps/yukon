package com.example.testkittarget

/** A tiny fixture instrumented by the end-to-end [io.github.lukedevops.yukon.testkit.YukonTestCollectorEndToEndTest]. */
class SampleTarget {
    fun exercised(): String = "used"

    fun neverCalled(): String = "dead code"

    /** Never called by anything, and itself the sole caller of [neverCalledHelper2]. */
    fun neverCalledHelper(): String = neverCalledHelper2()

    fun neverCalledHelper2(): String = "dead code too"
}
