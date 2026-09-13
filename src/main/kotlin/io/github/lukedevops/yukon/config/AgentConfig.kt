package io.github.lukedevops.yukon.config

import java.lang.System.Logger.Level
import java.net.URI
import java.time.Duration
import java.util.UUID

/**
 * Agent options, passed as comma-separated key=value pairs on the
 * `-javaagent:yukon-agent.jar=key=value,key=value` command line.
 */
data class AgentConfig(
    val serviceName: String,
    val serviceVersion: String?,
    val serviceInstanceId: String,
    val environment: String?,
    /** Base URL of the collector. The exporter appends `/v1/yukon/{deltas,manifest}`. */
    val collectorEndpoint: String,
    val flushInterval: Duration,
    /** Only types whose name starts with one of these prefixes are instrumented. Empty means every type is. */
    val instrumentedPackagePrefixes: List<String>,
    /**
     * Off unless explicitly enabled. Unlike every other capability here, a full classpath scan
     * has a cost that genuinely scales with an adopter's classpath size, so it does not inherit
     * this agent's usual "on unless configured otherwise" default.
     */
    val staticBaselineEnabled: Boolean,
) {
    companion object {
        private const val DEFAULT_ENDPOINT = "http://localhost:4319"
        private val DEFAULT_FLUSH_INTERVAL: Duration = Duration.ofSeconds(60)
        private val log = System.getLogger(AgentConfig::class.java.name)

        private val KNOWN_KEYS =
            setOf(
                "serviceName",
                "serviceVersion",
                "serviceInstanceId",
                "environment",
                "endpoint",
                "flushIntervalSeconds",
                "includePackages",
                "staticBaselineEnabled",
            )

        fun parse(agentArgs: String?): AgentConfig {
            val options = parseOptions(agentArgs)
            for (key in options.keys - KNOWN_KEYS) {
                log.log(Level.WARNING, "yukon: ignoring unknown agent option '$key' (known options: ${KNOWN_KEYS.sorted()})")
            }
            val prefixes = parseIncludePackages(options["includePackages"])
            if (prefixes.isEmpty()) {
                log.log(
                    Level.WARNING,
                    "yukon: includePackages is not set, so every class outside the JDK will be instrumented, third-party " +
                        "libraries included; set includePackages to your application's own packages",
                )
            }
            return AgentConfig(
                serviceName = options["serviceName"] ?: "unknown-service",
                serviceVersion = options["serviceVersion"],
                serviceInstanceId = options["serviceInstanceId"] ?: UUID.randomUUID().toString(),
                environment = options["environment"],
                collectorEndpoint = parseEndpoint(options["endpoint"]),
                flushInterval = parseFlushInterval(options["flushIntervalSeconds"]),
                instrumentedPackagePrefixes = prefixes,
                staticBaselineEnabled = options["staticBaselineEnabled"]?.toBoolean() ?: false,
            )
        }

        /**
         * A trailing dot on a prefix is dropped so `com.acme.` and `com.acme` mean the same thing;
         * [io.github.lukedevops.yukon.instrumentation.TypeMatchPolicy] matches on package boundaries
         * either way, so `com.acme` never also matches `com.acmeinternal`.
         */
        private fun parseIncludePackages(raw: String?): List<String> =
            raw
                ?.split(";")
                ?.map { it.trim().trimEnd('.') }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

        /**
         * The exporter appends `/v1/yukon/...` to this, so a trailing slash is dropped rather than
         * producing a `//` in every request path. A value that is not an absolute http(s) URL with
         * a host falls back to the default with a warning: left as is, `URI.create` would throw on
         * every attempt of every flush, so the collector would never be reached and the log would
         * fill with the same stack trace at each tick.
         */
        private fun parseEndpoint(raw: String?): String {
            if (raw == null) return DEFAULT_ENDPOINT
            val trimmed = raw.trim().trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull()
            if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host == null) {
                log.log(
                    Level.WARNING,
                    "yukon: endpoint must be an absolute http or https URL, ignoring '$raw' and using the default $DEFAULT_ENDPOINT",
                )
                return DEFAULT_ENDPOINT
            }
            return trimmed
        }

        /**
         * Falls back to the default for any non-positive or non-numeric value. This guards
         * [io.github.lukedevops.yukon.export.ExportScheduler]: `scheduleAtFixedRate` throws for a
         * non-positive period. That call happens inside `Agent.premain`, and the
         * `java.lang.instrument` contract says an uncaught exception there aborts the whole
         * target JVM. Without this fallback, one bad flag value could take down the entire app
         * at startup.
         */
        private fun parseFlushInterval(raw: String?): Duration {
            if (raw == null) return DEFAULT_FLUSH_INTERVAL
            val seconds = raw.toLongOrNull()?.takeIf { it > 0 }
            if (seconds == null) {
                log.log(
                    Level.WARNING,
                    "yukon: flushIntervalSeconds must be a positive integer, ignoring '$raw' " +
                        "and using the default of ${DEFAULT_FLUSH_INTERVAL.seconds}s",
                )
                return DEFAULT_FLUSH_INTERVAL
            }
            return Duration.ofSeconds(seconds)
        }

        private fun parseOptions(agentArgs: String?): Map<String, String> {
            if (agentArgs.isNullOrBlank()) return emptyMap()
            return agentArgs
                .split(",")
                .mapNotNull { pair ->
                    val separator = pair.indexOf('=')
                    if (separator <= 0) {
                        log.log(Level.WARNING, "yukon: ignoring malformed agent option '$pair' (expected key=value)")
                        null
                    } else {
                        pair.take(separator).trim() to pair.substring(separator + 1).trim()
                    }
                }.toMap()
        }
    }
}
