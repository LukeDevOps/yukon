package io.github.lukedevops.demo.server

/**
 * Loaded and never initialised. [main] names this object through a class literal, which loads the
 * class without running its static initialiser, and nothing else touches it. So the demo shows a
 * class the JVM loaded that is still never used: its initialiser, the only place this object's
 * instance is created, never runs, and [record] never runs either.
 */
object AuditLog {
    private val entries = mutableListOf<String>()

    fun record(entry: String) {
        entries += entry
    }
}
