package io.github.lukedevops.demo.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.lukedevops.demo.DemoPorts
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

private const val FREE_SHIPPING_THRESHOLD = 100.0

/**
 * Two endpoints and one feature-flagged class, illustrating three things static analysis can't
 * catch.
 *
 * `/checkout`'s free-shipping branch is only ever exercised one way, given how
 * [io.github.lukedevops.demo.client] calls it. `/promo` is reachable, but never called at all -
 * both are method-level "loaded but never hit" findings. [LegacyDiscountCalculator] is a level
 * further: its feature flag is always off in this demo, so the class itself never loads, which
 * only the static baseline (opted into below via `staticBaselineEnabled=true`) can report.
 * [formatTotal]'s two optional parameters add a fourth: [handleCheckout] always supplies
 * `decimals` and never supplies `currency`, giving the omission tier one finding of each kind.
 *
 * [handlePromo] also demonstrates an unreached cluster: it alone calls [applyPromoCode], which
 * alone calls [PromoRepository.find], so a client that never calls `/promo` leaves all three
 * methods, and `PromoRepository` itself, unreached together. [handleCheckout] demonstrates the
 * same call-edge attribution for a class that never loads at all: it calls
 * [LegacyDiscountCalculator]'s constructor and `apply`, and reads a static field of [LegacyRates],
 * both under the branch this demo never takes; the constructor and the field read are two
 * different ways an unloaded class is reached from [handleCheckout]. [handleCheckout] also holds
 * [respond] as a function-typed value and calls it through that value: kotlinc compiles the
 * reference to its own class, a body class under ADR 0024, whose `invoke` only the reference's
 * holder ever calls. Calling it on every request keeps that `invoke` and [respond] itself out of
 * every unreached cluster, which proves the body-class edge rather than manufacturing a finding.
 */
fun main() {
    val server = HttpServer.create(InetSocketAddress(DemoPorts.SERVER_PORT), 0)
    server.createContext("/checkout", ::handleCheckout)
    server.createContext("/promo", ::handlePromo)
    server.createContext("/__shutdown") { exchange ->
        respond(exchange, "shutting down")
        Thread { System.exit(0) }.start()
    }
    server.start()
    println("yukon demo server listening on ${DemoPorts.SERVER_PORT}")
}

private fun handleCheckout(exchange: HttpExchange) {
    val total = totalParam(exchange)
    val discounted =
        if (System.getenv("ENABLE_LEGACY_DISCOUNT") == "true") {
            LegacyDiscountCalculator().apply(total) + LegacyRates.FLAT_FEE
        } else {
            total
        }
    val message =
        if (discounted > FREE_SHIPPING_THRESHOLD) {
            "${describeOrder(discounted)} qualifies for free shipping"
        } else {
            "${describeOrder(discounted)} does not qualify for free shipping"
        }
    val send: (HttpExchange, String) -> Unit = ::respond
    send(exchange, message)
}

/**
 * Inline so every call from Kotlin, including [handleCheckout]'s, copies this body into the
 * caller instead of invoking this method. Its own probe reads zero no matter how often a request
 * comes in, so the demo report shows it under neither `NEVER HIT` nor a hit count.
 */
private inline fun describeOrder(total: Double): String = "order of ${formatTotal(total, decimals = 2)}"

/**
 * Always called with `decimals` supplied and never with `currency`: the demo report shows
 * `decimals` under `ALWAYS SUPPLIED` (the default is dead) and `currency` under `NEVER SUPPLIED`
 * (every caller took the default, so the parameter can go).
 */
private fun formatTotal(
    total: Double,
    currency: String = "GBP",
    decimals: Int = 2,
): String = "%.${decimals}f %s".format(total, currency)

private fun handlePromo(exchange: HttpExchange) {
    respond(exchange, applyPromoCode(exchange.requestURI.query ?: ""))
}

/**
 * Only [handlePromo] calls this, and the demo client never calls `/promo`, so neither this
 * method nor [PromoRepository.find] ever runs. That grows the unreached cluster rooted at
 * `handlePromo` by one more method and, through `find`, one more never-loaded class.
 */
private fun applyPromoCode(code: String): String {
    val discount = PromoRepository.find(code)
    return "promo code applied: $discount% off"
}

private fun totalParam(exchange: HttpExchange): Double {
    val query = exchange.requestURI.query ?: return 0.0
    val value =
        query
            .split("&")
            .map { it.split("=", limit = 2) }
            .firstOrNull { it.first() == "total" }
            ?.getOrNull(1)
    return value?.toDoubleOrNull() ?: 0.0
}

private fun respond(
    exchange: HttpExchange,
    message: String,
) {
    val bytes = message.toByteArray(StandardCharsets.UTF_8)
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}
