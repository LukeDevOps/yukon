package com.example.testkittarget

/** A tiny fixture instrumented by the end-to-end [io.github.lukedevops.yukon.testkit.YukonTestCollectorEndToEndTest]. */
class SampleTarget {
    fun exercised(): String = "used"

    fun neverCalled(): String = "dead code"
}
