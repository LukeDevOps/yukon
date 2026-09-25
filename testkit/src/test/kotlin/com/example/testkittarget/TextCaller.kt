package com.example.testkittarget

/**
 * Calls the multi-file functions in `TestkitGreetings.kt`. [exercised] loads the facade and the
 * part, and [neverCalled] is the only caller of [partGreeting].
 */
class TextCaller {
    fun exercised(): String = partHello()

    fun neverCalled(): String = partGreeting("x")
}
