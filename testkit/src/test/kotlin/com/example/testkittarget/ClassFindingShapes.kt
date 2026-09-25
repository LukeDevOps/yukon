package com.example.testkittarget

/**
 * The class-finding shapes of yukon-server's ADR 0034, for
 * [io.github.lukedevops.yukon.testkit.YukonTestCollectorEndToEndTest]. The test loads [AuditTrail],
 * [LinePrinter] and [Greeter] with `Class.forName(name, false, loader)`, which loads a class without
 * initialising it. It initialises [ReportWriter] without creating one, and exercises the rest.
 */
object AuditTrail {
    private val entries = mutableListOf<String>()

    /** Never runs, since the object is never initialised. */
    fun record(entry: String) {
        entries += entry
    }
}

/**
 * Loaded and never instantiated. It has no static initialiser. [print]'s lambda compiles to a static
 * method, which folds with [print] into the class finding.
 */
class LinePrinter(
    private val width: Int,
) {
    /** Never runs, since no instance exists. */
    fun print(lines: List<String>): String = lines.joinToString("\n") { it.padEnd(width) }
}

/**
 * Initialised and never instantiated. [write] is the only caller of [TextUtil.pad], so the class
 * roots a cluster that reaches beyond its own methods.
 */
class ReportWriter(
    private val width: Int,
) {
    /** Never runs, since no instance exists. */
    fun write(line: String): String = TextUtil().pad(line, width)

    companion object {
        /** A static method of a never-instantiated class. It can still run, so it is never folded. */
        @JvmStatic
        fun footer(): String = "end"
    }
}

/** Created and used through [trim], while [pad] is called only by [ReportWriter.write]. */
class TextUtil {
    /** Runs. */
    fun trim(text: String): String = text.trim()

    /** Never runs. */
    fun pad(
        text: String,
        width: Int,
    ): String = text.padEnd(width)
}

/** Always built from pence, so its pounds-and-pence constructor is an unused overload. */
class Amount(
    private val pence: Long,
) {
    constructor(pounds: Int, pence: Int) : this(pounds * 100L + pence)

    /** Runs. */
    fun inPounds(): Double = pence / 100.0
}

/**
 * Always given its scale, so the `Scaled(Double)` overload `@JvmOverloads` adds never runs. The
 * agent marks that overload generated, so it is never an unused overload.
 */
class Scaled
    @JvmOverloads
    constructor(
        amount: Double,
        scale: Int = 2,
    ) {
        /** Runs. */
        val value: Double = amount * scale
    }

/** A holder of statics with a private constructor that never runs. Nobody meant to create one. */
class Utils private constructor() {
    companion object {
        /** Runs, through the static method `@JvmStatic` adds to [Utils]. */
        @JvmStatic
        fun name(): String = "utils"
    }
}

/** A Kotlin object that is used: its constructor runs inside its static initialiser. */
object Counters {
    /** Runs. */
    fun size(): Int = 0

    /** Never runs. */
    fun unused(): Int = 1
}

/** An interface with a default method: no constructor, so never judged never instantiated. */
interface Greeter {
    /** Never runs. */
    fun greet(): String = "hello"
}
