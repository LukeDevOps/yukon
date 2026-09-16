package com.example.target

import com.example.other.OtherTarget

enum class Suit { HEARTS, SPADES }

object Config {
    @JvmField
    var flag: Boolean = true
}

/** Reads and writes its own static field from a method on the same class, never through another class's edge. */
object SelfStaticUser {
    @JvmField
    var counter: Int = 0

    fun touchOwnCounter(): Int {
        counter = 5
        return counter
    }
}

class FinalMethodTarget {
    fun method(): Int = 5
}

/**
 * Exercises every field-use call-edge shape [io.github.lukedevops.yukon.instrumentation.branch.BranchSiteAnalyzer]
 * records: a static read and a static write on another in-scope class (deduplicated to one edge),
 * an out-of-scope static read, an instance field on another in-scope class (no edge beyond its
 * own existing constructor edge), and a call to another in-scope class's final method.
 */
class StaticUseTarget {
    fun readEnumConstant(): Suit = Suit.HEARTS

    fun readAndWriteConfigFlag(): Boolean {
        Config.flag = false
        return Config.flag
    }

    fun readJavaStaticField(): Int = OtherTarget.counter

    fun writeJavaStaticField() {
        OtherTarget.counter = 5
    }

    fun readSystemOut(): java.io.PrintStream = System.out

    fun touchesOtherFieldAndConstructs(): Int {
        val other = OtherTarget()
        other.value = 3
        return other.value
    }

    fun callsFinalMethod(target: FinalMethodTarget): Int = target.method()
}
