@file:JvmMultifileClass
@file:JvmName("DemoText")

package io.github.lukedevops.demo.server

/**
 * One file of the multi-file facade `DemoText`. kotlinc puts this body in the part
 * `DemoText__DemoTextGreetingKt` and a forwarder on `DemoText`, which is what [handleCheckout]'s
 * call names. The agent marks the forwarder generated and follows the call through it to this
 * function. See ADR 0041.
 */
fun checkoutGreeting(): String = "thank you"
