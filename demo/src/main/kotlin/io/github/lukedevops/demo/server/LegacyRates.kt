package io.github.lukedevops.demo.server

/**
 * Guarded the same way [LegacyDiscountCalculator] is: the environment flag that reaches it is
 * always off in this demo, so this object never loads either. [handleCheckout] reads [FLAT_FEE]
 * directly and never constructs this object, proving the same "declared, never loaded" signal for
 * a class used only through a static field rather than through `new`.
 */
object LegacyRates {
    @JvmField
    val FLAT_FEE: Double = 1.5
}
