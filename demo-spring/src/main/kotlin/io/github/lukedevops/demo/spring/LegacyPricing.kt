package io.github.lukedevops.demo.spring

/**
 * Guarded by a feature flag that is always off in this demo, so this class never loads at all.
 * Mirrors the plain demo server's `LegacyDiscountCalculator`, but here the static scan has to
 * read this class out of `BOOT-INF/classes` inside a real Spring Boot fat jar rather than off a
 * flat classpath directory.
 */
class LegacyPricing {
    fun apply(price: Double): Double = price * 0.95
}
