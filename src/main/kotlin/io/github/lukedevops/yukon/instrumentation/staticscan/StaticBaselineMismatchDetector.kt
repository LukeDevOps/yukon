package io.github.lukedevops.yukon.instrumentation.staticscan

import java.util.concurrent.ConcurrentHashMap

/**
 * Detects a class that registers dynamically without having been in the static baseline
 * computed at startup: proof the static scan missed it for this process, most likely because
 * this deployment's classloading is discovered by something other than the JVM's own launch
 * arguments (an app server's own deploy config, a plugin loader scanning a directory). See
 * "Static baseline" in this project's `CLAUDE.md`.
 *
 * This is a purely local, diagnostic signal. It needs no collector: [knownDeclaredClassNames] is
 * this same process's own scan result, not anything received back over the wire.
 */
class StaticBaselineMismatchDetector {
    /** Null until the static scan for this process completes. No comparison is possible before then. */
    @Volatile
    var knownDeclaredClassNames: Set<String>? = null

    private val alreadyWarned = ConcurrentHashMap.newKeySet<String>()

    /** True the first time [className] is found missing from [knownDeclaredClassNames]; false every time after. */
    fun shouldWarnAbout(className: String): Boolean {
        val known = knownDeclaredClassNames ?: return false
        if (className in known) return false
        return alreadyWarned.add(className)
    }
}
