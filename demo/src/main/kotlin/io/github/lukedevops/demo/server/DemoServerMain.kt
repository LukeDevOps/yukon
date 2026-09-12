package io.github.lukedevops.demo.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.lukedevops.demo.DemoPorts
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

private const val FREE_SHIPPING_THRESHOLD = 100.0

/**
 * Two endpoints, illustrating the two things static analysis can't catch:
 * `/checkout`'s free-shipping branch is only ever exercised one way given
 * how [io.github.lukedevops.demo.client] calls it, and `/promo` is never
 * called at all despite being reachable.
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
    val message =
        if (total > FREE_SHIPPING_THRESHOLD) {
            "order of $total qualifies for free shipping"
        } else {
            "order of $total does not qualify for free shipping"
        }
    respond(exchange, message)
}

private fun handlePromo(exchange: HttpExchange) {
    respond(exchange, "promo code applied")
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
