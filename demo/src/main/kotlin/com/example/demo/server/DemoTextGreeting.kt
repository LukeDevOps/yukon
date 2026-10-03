@file:JvmMultifileClass
@file:JvmName("DemoText")

package com.example.demo.server

/**
 * One file of the multi-file facade `DemoText`. kotlinc puts this body in the part
 * `DemoText__DemoTextGreetingKt` and a forwarder on `DemoText`, which is what [handleCheckout]'s
 * call names. The agent marks the forwarder generated and follows the call through it to this
 * function.
 */
fun checkoutGreeting(): String = "thank you"
