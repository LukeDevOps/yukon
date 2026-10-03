package com.example.target.jvmdefaultdisable

import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** A suspension point that never suspends, so the default body below has a state machine. */
suspend fun pauseHere(): Int = suspendCoroutineUninterceptedOrReturn { 1 }

/**
 * Compiled with `-jvm-default=disable`, so [run]'s body and state machine live in
 * `SuspendDefaultInterface$DefaultImpls.run`, while kotlinc names its continuation class after the
 * interface, `SuspendDefaultInterface$run$1`.
 */
interface SuspendDefaultInterface {
    suspend fun run(x: Int): Int {
        val a = pauseHere()
        return if (x > a) 1 else 2
    }
}
