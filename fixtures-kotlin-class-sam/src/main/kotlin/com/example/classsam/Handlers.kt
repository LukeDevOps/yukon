package com.example.classsam

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer

/** The function most handlers here forward to. */
fun handleOrder(exchange: HttpExchange) {
    exchange.sendResponseHeaders(204, -1)
    exchange.close()
}

private fun handleSecret(exchange: HttpExchange) {
    exchange.sendResponseHeaders(204, -1)
    exchange.close()
}

/** A reference class: synthetic, and its `handle` calls [handleOrder]. */
fun reference(): HttpHandler = HttpHandler(::handleOrder)

/** A reference class whose `handle` reaches the private function through an `access$` accessor. */
fun privateReference(): HttpHandler = HttpHandler(::handleSecret)

/** A lambda compiled to a class. It is not synthetic, so its own `handle` is probed. */
fun lambda(): HttpHandler = HttpHandler { handleOrder(it) }

/** Registers a reference class straight from the call that passes it. */
fun register(server: HttpServer) {
    server.createContext("/order", ::handleOrder)
}

/** A `$sam$` wrapper: its `handle` calls the function value it holds, never a named function. */
fun fromValue(function: (HttpExchange) -> Unit): HttpHandler = HttpHandler(function)

/** A `$sam$` wrapper around a reference to [handleOrder]. */
fun wrapped(): HttpHandler = fromValue(::handleOrder)
