@file:JvmMultifileClass
@file:JvmName("TestkitText")

package com.example.testkittarget

/**
 * One file of the multi-file facade `TestkitText`, for
 * [dev.otherlode.testkit.OtherlodeTestCollectorEndToEndTest]. kotlinc puts both bodies in
 * the synthetic part `TestkitText__TestkitGreetingsKt`, and every Kotlin call goes through a
 * forwarder on `TestkitText`. The forwarder is generated and passes the call through, so a call
 * edge into it names the part's function.
 */
fun partHello(): String = "hello"

/** Called only from [TextCaller.neverCalled], through the facade's forwarder. */
fun partGreeting(name: String): String = "hello $name"
