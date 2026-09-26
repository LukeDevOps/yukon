package com.example.testkittarget

/**
 * Branch sites of each shape server ADR 0031's fold rule tells apart, for
 * [io.github.lukedevops.yukon.testkit.YukonTestCollectorEndToEndTest]. The test creates one
 * instance and calls every method but [neverCalled].
 */
class SiteFolds {
    /** Never called, so both its sites fold into its own row, the routine null side of `?:` included. */
    fun neverCalled(
        flag: Boolean,
        text: String?,
    ): Int {
        val length = text?.length ?: 0
        return if (flag) length + 1 else length - 1
    }

    /**
     * Called with every flag false. The outcome of `if (outer)` that enters the block never runs, so
     * the `middle` site behind it folds, and so does the `inner` site behind the `middle` outcome.
     */
    fun nested(
        outer: Boolean,
        middle: Boolean,
        inner: Boolean,
    ): Int {
        var total = 0
        if (outer) {
            total += 1
            if (middle) {
                total += 2
                if (inner) total += 4
            }
        }
        return total
    }

    /** Called with [first] true and [second] false. The guard of the `second` site ran, so that site stays listed. */
    fun guardRan(
        first: Boolean,
        second: Boolean,
    ): Int {
        var total = 0
        if (first) {
            total += 1
            if (second) total += 2
        }
        return total
    }

    /**
     * Called with a non-null [count]. The null side of `?:` is routine and never ran, so the `flag`
     * site behind it stays listed.
     */
    fun behindRoutine(
        count: Int?,
        flag: Boolean,
    ): Int = count ?: if (flag) 1 else 2
}

/**
 * A class whose only method is its constructor, loaded but never constructed. Its constructor is
 * never hit but is not a row, since it is not an unused overload and the class has no finding, and
 * its site still folds into it: that code never ran.
 */
class SiteFoldsLoneConstructor(
    flag: Boolean,
) {
    init {
        if (flag) Thread.yield()
    }
}
