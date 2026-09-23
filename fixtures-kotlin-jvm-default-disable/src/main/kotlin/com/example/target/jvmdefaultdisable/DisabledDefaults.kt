package com.example.target.jvmdefaultdisable

/**
 * Compiled with `-jvm-default=disable`, so each default body below lives in
 * `DisabledDefaultInterface$DefaultImpls` and the interface methods are abstract. Mirrors
 * `com.example.target.GeneratedInterface`, which compiles under the root build's default mode
 * and so gets a forwarding `$DefaultImpls` instead.
 *
 * [callsPrivate] is the near miss: its body loads its arguments, makes one `invokestatic` and
 * returns, but the call's owner is `$DefaultImpls` itself, where [priv]'s body lives, not the
 * interface.
 */
interface DisabledDefaultInterface {
    fun withBranch(x: Int): Int {
        if (x > 3) return 1
        return 2
    }

    fun withBody(): Int = 42

    val label: String
        get() = "disabled"

    fun callsPrivate(x: Int): Int = priv(x)

    private fun priv(x: Int): Int = x + 1
}

class DisabledDefaultInterfaceImpl : DisabledDefaultInterface
