package io.github.lukedevops.demo.server

/**
 * An amount in pence with an unused overload. [handleCheckout] builds every `Money` from pence, so
 * the pounds-and-pence constructor below never runs while the class itself is in use.
 */
class Money(
    private val pence: Long,
) {
    constructor(pounds: Int, pence: Int) : this(pounds * 100L + pence)

    val amount: Double
        get() = pence / 100.0
}
