package io.github.lukedevops.demo.shapes

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

/**
 * Runs once under the agent and exits, touching code shapes to read yukon-server's report and UI
 * against, each with a part that never runs: Java anonymous classes and a lambda ([JavaShapes]), a
 * companion object, a data class, and a suspend function. The first argument is how many seconds
 * to wait before exiting, so the static baseline scan and a regular flush land first.
 */
fun main(args: Array<String>) {
    JavaShapes.run()
    println(Tariff.standard().describe())
    val invoice = Invoice(1, 10.0)
    println(invoice.copy(total = 12.0))
    println(runSuspending { settle(invoice, express = false) })
    Thread.sleep(args.first().toLong() * 1000)
}

/** A tariff made only through its companion object, whose [Companion.premium] is never called. */
class Tariff private constructor(
    private val name: String,
    private val rate: Double,
) {
    fun describe(): String = "$name tariff at $rate"

    companion object {
        fun standard(): Tariff = Tariff("standard", 0.2)

        fun premium(): Tariff = Tariff("premium", 0.1)
    }
}

/** A data class: `copy` and `toString` run, the rest of what kotlinc generates does not, and [isLarge] is never called. */
data class Invoice(
    val id: Int,
    val total: Double,
) {
    fun isLarge(): Boolean = total > 1000.0
}

/** Settles [invoice], never by express, so the express fee is never charged. */
suspend fun settle(
    invoice: Invoice,
    express: Boolean,
): String {
    val fee = if (express) expressFee() else 0.0
    handOff()
    return "settled invoice ${invoice.id} with fee $fee"
}

private suspend fun expressFee(): Double {
    handOff()
    return 5.0
}

/** A suspension point that resumes at once, so kotlinc builds a state machine around it. */
private suspend fun handOff() = suspendCoroutine { it.resume(Unit) }

/** Runs [block] to completion on this thread with the standard library alone. */
private fun <T> runSuspending(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return checkNotNull(outcome) { "the coroutine suspended and never resumed" }.getOrThrow()
}
