package io.github.lukedevops.demo.server

import kotlin.math.pow
import kotlin.math.round

/**
 * An amount rounded to [scale] decimal places. `@JvmOverloads` makes kotlinc add a `Price(Double)`
 * overload that nobody wrote. [handleCheckout] always passes the scale, so that overload never
 * runs, and the agent marks it generated so it is never read as an unused overload.
 */
class Price
    @JvmOverloads
    constructor(
        amount: Double,
        scale: Int = 2,
    ) {
        val amount: Double = round(amount * 10.0.pow(scale)) / 10.0.pow(scale)
    }
