package com.example.target.keypairs

/** Copied into every call site by kotlinc; each copy's site fingerprints the same way. */
inline fun isPositive(x: Int): Boolean = x > 0

/**
 * Exercises the branch key's collision rule against a real inlined condition rather than a
 * hand-built [io.github.lukedevops.yukon.instrumentation.branch.BranchSite]:
 *
 * [calledTwiceInOneMethod] inlines [isPositive] twice in one method, so both copies share a
 * method name, descriptor, fingerprint and origin class, and collide.
 *
 * [calledOnceInMethodA] and [calledOnceInMethodB] each inline it once, in different methods, so
 * neither collides with the other and each keeps its own key.
 */
class InlineKeyCollision {
    fun calledTwiceInOneMethod(
        a: Int,
        b: Int,
    ): Int {
        var total = 0
        if (isPositive(a)) total++
        if (isPositive(b)) total++
        return total
    }

    fun calledOnceInMethodA(a: Int): Int = if (isPositive(a)) 1 else 0

    fun calledOnceInMethodB(b: Int): Int = if (isPositive(b)) 1 else 0
}
