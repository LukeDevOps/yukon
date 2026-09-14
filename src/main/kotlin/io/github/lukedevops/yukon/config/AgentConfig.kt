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
    /** Base URL of the collector. The exporter appends `/v1/yukon/{deltas,manifest,static-baseline}`. */
    val collectorEndpoint: String,
    /**
     * Sent to the collector as `Authorization: Bearer <token>`. Prefer the `YUKON_AUTH_TOKEN`
     * environment variable over the `authToken` agent option: a `-javaagent` argument is visible
     * to every user on the host through `ps` and `/proc/<pid>/cmdline`, so a secret passed that
     * way is readable by anyone who can list processes.
     */
    val authToken: String?,
    val flushInterval: Duration,
    /** Only types whose name starts with one of these prefixes are instrumented. Empty means every type is. */
    val instrumentedPackagePrefixes: List<String>,
    /**
     * A type under one of these prefixes is never instrumented, even if [instrumentedPackagePrefixes]
     * also matches it. Exclusion always wins over inclusion.
     */
    val excludedPackagePrefixes: List<String>,
    /**
     * Off unless explicitly enabled. Unlike every other capability here, a full classpath scan
     * has a cost that genuinely scales with an adopter's classpath size, so it does not inherit
     * this agent's usual "on unless configured otherwise" default.
     */
    val staticBaselineEnabled: Boolean,
    /**
     * On by default. When false, [io.github.lukedevops.yukon.Agent] logs one line and does
     * nothing else: no bootstrap holder, no transformer, no exporter, no scheduler. Lets an
     * adopter bake `-javaagent` into a container image and switch the agent off per deployment
     * with `YUKON_ENABLED=false`, with no image rebuild.
     */
    val enabled: Boolean,
    /**
     * On by default. One switch for every endpoint module (Spring, Ktor, `jdk.httpserver`,
     * JAX-RS); there are no per-framework flags, by design. See ADR 0017.
     */
    val endpointsEnabled: Boolean,
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
                "authToken",
                "flushIntervalSeconds",
                "includePackages",
                "excludePackages",
                "staticBaselineEnabled",
                "enabled",
                "endpointsEnabled",
            )

        /**
         * Every option in [KNOWN_KEYS] resolves the same way: the agent-args string wins, then a
         * JVM system property, then an environment variable, then the option's own built-in
         * default. [OptionNames] derives the property and environment variable names from the
         * option name itself, so the three sources can never drift apart from each other.
         *
         * A blank value at any source counts as unset and falls through to the next one, the
         * same way a blank `authToken` option already fell through to `YUKON_AUTH_TOKEN`.
         */
        fun parse(
            agentArgs: String?,
            env: (String) -> String? = System::getenv,
            systemProperties: (String) -> String? = System::getProperty,
        ): AgentConfig {
            val options = parseOptions(agentArgs)
            for (key in options.keys - KNOWN_KEYS) {
                log.log(Level.WARNING, "yukon: ignoring unknown agent option '$key' (known options: ${KNOWN_KEYS.sorted()})")
            }

            fun resolve(key: String): String? = resolveOption(key, options, systemProperties, env)

            val prefixes = parsePackagePrefixes(resolve("includePackages"))
            if (prefixes.isEmpty()) {
                log.log(
                    Level.WARNING,
                    "yukon: includePackages is not set, so every class outside the JDK will be instrumented, third-party " +
                        "libraries included; set includePackages to your application's own packages",
                )
            }
            val excludedPrefixes = parsePackagePrefixes(resolve("excludePackages"))
            val endpoint = parseEndpoint(resolve("endpoint"))
            val authToken = resolve("authToken")
            if (authToken != null && endpoint.startsWith("http://")) {
                log.log(Level.WARNING, "yukon: endpoint uses plain http, so the auth token is sent unencrypted")
            }
            return AgentConfig(
                serviceName = resolve("serviceName") ?: "unknown-service",
                serviceVersion = resolve("serviceVersion"),
                serviceInstanceId = resolve("serviceInstanceId") ?: UUID.randomUUID().toString(),
                environment = resolve("environment"),
                collectorEndpoint = endpoint,
                authToken = authToken,
                flushInterval = parseFlushInterval(resolve("flushIntervalSeconds")),
                instrumentedPackagePrefixes = prefixes,
                excludedPackagePrefixes = excludedPrefixes,
                staticBaselineEnabled = parseBoolean("staticBaselineEnabled", resolve("staticBaselineEnabled"), default = false),
                enabled = parseBoolean("enabled", resolve("enabled"), default = true),
                endpointsEnabled = parseBoolean("endpointsEnabled", resolve("endpointsEnabled"), default = true),
            )
        }

        /**
         * Walks the three sources for [key] in precedence order: the agent-args option, then the
         * matching system property, then the matching environment variable. A blank value at any
         * source is treated as unset, so it falls through instead of masking a value from a
         * lower-precedence source.
         */
        private fun resolveOption(
            key: String,
            options: Map<String, String>,
            systemProperties: (String) -> String?,
            env: (String) -> String?,
        ): String? =
            valueOrNull(options[key])
                ?: valueOrNull(systemProperties(OptionNames.systemProperty(key)))
                ?: valueOrNull(env(OptionNames.environmentVariable(key)))

        private fun valueOrNull(raw: String?): String? = raw?.trim()?.ifBlank { null }

        /**
         * Accepts `true`/`false` case-insensitively. Any other non-blank value logs a WARNING
         * and falls back to [default], the same way an out-of-range [parseFlushInterval] value
         * does, rather than silently reading as false.
         */
        private fun parseBoolean(
            key: String,
            raw: String?,
            default: Boolean,
        ): Boolean {
            if (raw == null) return default
            val parsed = raw.lowercase().toBooleanStrictOrNull()
            if (parsed == null) {
                log.log(Level.WARNING, "yukon: $key must be 'true' or 'false', ignoring '$raw' and using the default of $default")
                return default
            }
            return parsed
        }

        /**
         * A trailing dot on a prefix is dropped so `com.acme.` and `com.acme` mean the same thing;
         * [io.github.lukedevops.yukon.instrumentation.TypeMatchPolicy] matches on package boundaries
         * either way, so `com.acme` never also matches `com.acmeinternal`. Shared by
         * `includePackages` and `excludePackages`, which use the same `;`-separated syntax.
         */
        private fun parsePackagePrefixes(raw: String?): List<String> =
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
