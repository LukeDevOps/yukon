@file:JvmMultifileClass
@file:JvmName("TestkitText")

package com.example.testkittarget

/**
 * One file of the multi-file facade `TestkitText`, for
 * [io.github.lukedevops.yukon.testkit.YukonTestCollectorEndToEndTest]. kotlinc puts both bodies in
 * the synthetic part `TestkitText__TestkitGreetingsKt`, and every Kotlin call goes through a
 * forwarder on `TestkitText`. See ADR 0041.
 */
fun partHello(): String = "hello"

/** Called only from [TextCaller.neverCalled], through the facade's forwarder. */
fun partGreeting(name: String): String = "hello $name"
