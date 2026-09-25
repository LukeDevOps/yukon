package com.example.target

import com.sun.net.httpserver.HttpServer

/**
 * A Kotlin interface with one abstract method. kotlinc converts a lambda to it through an
 * `invokedynamic`, as it does for a Java interface.
 */
fun interface IntStep {
    fun step(value: Int): Int
}

/**
 * Each interface a kotlinc creation edge can name (ADR 0042). [register] passes a lambda to a Java
 * interface parameter, as the demo's `createContext` does. [startsThread] passes one to
 * `Thread(Runnable)`. [funInterface] converts one to [IntStep]. [functionType] makes a plain
 * function-typed value, which implements `Function1`. [objectExpression] creates a body class
 * with `new`, so its edge names no interface.
 */
class ImplementedInterfaceTarget {
    fun register(server: HttpServer) {
        server.createContext("/implemented") { exchange -> exchange.close() }
    }

    fun startsThread(): Thread = Thread { touch() }

    fun funInterface(): Int = IntStep { it + 1 }.step(1)

    fun functionType(): Int {
        val f: (Int) -> Int = { it }
        return f(2)
    }

    fun objectExpression(): Runnable =
        object : Runnable {
            override fun run() {
                touch()
            }
        }

    fun touch(): Int = 1
}
