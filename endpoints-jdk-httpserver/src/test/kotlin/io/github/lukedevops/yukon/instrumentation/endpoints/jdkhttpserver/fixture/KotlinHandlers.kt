package io.github.lukedevops.yukon.instrumentation.endpoints.jdkhttpserver.fixture

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer

/** A Kotlin lambda passed as a Java functional interface. kotlinc compiles the SAM conversion to an `invokedynamic`. */
fun kotlinLambda(): HttpHandler = HttpHandler { exchange -> respondFromKotlin(exchange) }

/** Registers a reference to [handleFromKotlin], written the way the demo registers `/checkout`. */
fun registerKotlinReference(
    server: HttpServer,
    path: String,
) {
    server.createContext(path, ::handleFromKotlin)
}

/** The target of the reference [registerKotlinReference] passes. */
fun handleFromKotlin(exchange: HttpExchange) = respondFromKotlin(exchange)

private fun respondFromKotlin(exchange: HttpExchange) {
    exchange.sendResponseHeaders(200, -1)
    exchange.close()
}
