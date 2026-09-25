package com.example.testkittarget

/**
 * The demo's checkout shape, for [io.github.lukedevops.yukon.testkit.YukonTestCollectorEndToEndTest].
 * The true side of one `if` constructs [LegacyCalculator], calls [LegacyCalculator.apply] and reads
 * [LegacyFees.FLAT_FEE], so every method of both classes runs only through that side.
 */
class LegacyCheckout {
    fun total(
        amount: Double,
        legacy: Boolean,
    ): Double =
        if (legacy) {
            LegacyCalculator().apply(amount) + LegacyFees.FLAT_FEE
        } else {
            amount
        }
}

/** Reached only from the true side of [LegacyCheckout.total]'s `if`. */
class LegacyCalculator {
    fun apply(amount: Double): Double = amount * 0.9
}

/** Read only through its static field, from the true side of [LegacyCheckout.total]'s `if`. */
object LegacyFees {
    @JvmField
    val FLAT_FEE: Double = 1.5
}
