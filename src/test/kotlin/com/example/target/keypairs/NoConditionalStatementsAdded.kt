package com.example.target.keypairs

/** One condition on `x`, nothing before it. */
class NoConditionalStatementsAddedV1 {
    fun check(x: Int): String = if (x > 0) "pos" else "non-pos"
}

/** The same condition on `x`, with a log call, a new local, and a reassignment added before it. */
class NoConditionalStatementsAddedV2 {
    fun check(x: Int): String {
        println("checking")
        var extra = 1
        extra = extra + 1
        return if (x > 0) "pos" else "non-pos"
    }
}
