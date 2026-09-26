package io.github.lukedevops.yukon.config

import java.lang.System.Logger.Level

/**
 * Screens the values a service name or namespace is read from. The server shows a service at a
 * URL path that holds its namespace and its name as segments, and browsers drop a `.` or `..`
 * segment even when it is percent-escaped. So neither value can name a service, and the collector
 * rejects a payload that carries one. See ADR 0045.
 */
internal object ServiceIdentityValues {
    private val log = System.getLogger(ServiceIdentityValues::class.java.name)

    /** Says whether [value] is `.` or `..`. */
    fun isDotSegment(value: String): Boolean = value == "." || value == ".."

    /**
     * Returns [value], or null when it is `.` or `..`. A skipped value logs a WARNING that names
     * [source], so the next source can give the value instead.
     */
    fun usable(
        value: String?,
        source: String,
    ): String? {
        if (value == null || !isDotSegment(value)) return value
        log.log(Level.WARNING, "yukon: ignoring '$value' from $source, since no URL path can name a service or namespace '$value'")
        return null
    }
}
