package com.example.target.keypairs

/** An `if` expression sits inside the outer condition itself, with no statement between them. */
class TernaryEditBeforeMergeV1 {
    fun check(
        flag: Boolean,
        x: Int,
        y: Int,
    ): String = if ((if (flag) x else y) > 0) "pos" else "non-pos"
}

/**
 * The inner `if`'s true arm is edited from `x` to `x + 1`. That arm runs before the operand
 * stack is next empty, since the jump over the false arm leaves the depth unknown and the false
 * arm's label restarts it at zero. So the outer condition's window starts at the false arm and
 * leaves this edit out. See ADR 0031's Consequences.
 */
class TernaryEditBeforeMergeV2 {
    fun check(
        flag: Boolean,
        x: Int,
        y: Int,
    ): String = if ((if (flag) x + 1 else y) > 0) "pos" else "non-pos"
}

/** The inner `if`'s false arm is edited from `y` to `y + 1`, which is inside the outer condition's window. */
class TernaryEditBeforeMergeV3 {
    fun check(
        flag: Boolean,
        x: Int,
        y: Int,
    ): String = if ((if (flag) x else y + 1) > 0) "pos" else "non-pos"
}
