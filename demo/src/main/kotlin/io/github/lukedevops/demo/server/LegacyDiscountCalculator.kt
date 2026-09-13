package io.github.lukedevops.demo.server

/**
 * Guarded by a feature flag that is always off in this demo, so this class never loads at all.
 * Illustrates the static baseline's own signal: not "loaded but never hit" (that is what
 * `/promo` demonstrates), but "declared in the code and never even constructed."
 */
class LegacyDiscountCalculator {
    fun apply(total: Double): Double = total * 0.9
}
