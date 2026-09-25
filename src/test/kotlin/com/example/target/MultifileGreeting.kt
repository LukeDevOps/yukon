@file:JvmMultifileClass
@file:JvmName("MultifileText")

package com.example.target

/**
 * One file of the multi-file facade `MultifileText`. kotlinc puts the body in the synthetic part
 * `MultifileText__MultifileGreetingKt` and a forwarder of the same name on `MultifileText`. See
 * ADR 0041.
 */
fun multifileGreeting(name: String): String = "hello $name"
