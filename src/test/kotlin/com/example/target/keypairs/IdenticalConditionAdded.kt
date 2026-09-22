package com.example.target.keypairs

/** One condition on `x`. */
class IdenticalConditionAddedV1 {
    fun check(x: Int): String {
        if (x > 0) return "pos"
        return "non-pos"
    }
}

/** The identical condition on `x`, checked a second time right after the first. */
class IdenticalConditionAddedV2 {
    fun check(x: Int): String {
        if (x > 0) return "pos"
        if (x > 0) return "still-pos"
        return "non-pos"
    }
}
