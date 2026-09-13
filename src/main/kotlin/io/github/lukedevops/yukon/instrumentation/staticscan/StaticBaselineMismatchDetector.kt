package io.github.lukedevops.yukon.instrumentation.staticscan

import java.util.concurrent.ConcurrentHashMap

/**
 * Detects a class that registers dynamically without having been in the static baseline
 * computed at startup: proof the static scan missed it for this process, most likely because
 * this deployment's classloading is discovered by something other than the JVM's own launch
 * arguments (an app server's own deploy config, a plugin loader scanning a directory). See
 * "Static baseline" in this project's `CLAUDE.md`.
 *
 * This is a purely local, diagnostic signal. It needs no collector: [knownClassNames] is this
 * same process's own scan result, not anything received back over the wire.
 */
class StaticBaselineMismatchDetector {
    /**
     * Every class name the static scan saw, in any bucket (declared, unsafe, unreadable, unprobed).
     * A class the scan saw but could not classify is still not a blind spot. Null until the scan
     * for this process completes; no comparison is possible before then.
     */
    @Volatile
    var knownClassNames: Set<String>? = null

    private val alreadyWarned = ConcurrentHashMap.newKeySet<String>()

    /** True the first time [className] is found missing from [knownClassNames]; false every time after. */
    fun shouldWarnAbout(className: String): Boolean {
        val known = knownClassNames ?: return false
        if (className in known) return false
        return alreadyWarned.add(className)
    }
}
