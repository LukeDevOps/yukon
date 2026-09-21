package com.example.target

import com.example.library.Lib

/**
 * Pass-throughs and bodies for the reference attribution rules of ADR 0030, which follow ADR 0024's
 * call-edge rules: `Inner` reaches the private [secret] through the `access$secret` accessor kotlinc
 * generates on this class, [callsDefault] reaches [withDefault] through `withDefault$default`, whose
 * body evaluates the default, and [makesLambda] and [makesObject] create a lambda body and a body
 * class that each keep their own references.
 */
class ReferenceAttributionTarget {
    private fun secret(): Lib.Secret? = null

    inner class Inner {
        fun callSecret(): Any? = secret()
    }

    fun withDefault(value: Any? = Lib.DefaultValue.make()): Any? = value

    fun callsDefault(): Any? = withDefault()

    fun callsHelper(): Any? = helper()

    private fun helper(): Any? = Lib.Helper()

    fun makesLambda(): () -> Unit = { Lib.LambdaBody.run() }

    fun makesObject(): Runnable =
        object : Runnable {
            override fun run() {
                Lib.BodyRef.run()
            }
        }
}

/** Calls [ReferenceAttributionTarget.withDefault] from another class, through its cross-class `withDefault$default`. */
class ReferenceDefaultCaller {
    fun call(target: ReferenceAttributionTarget): Any? = target.withDefault()
}
