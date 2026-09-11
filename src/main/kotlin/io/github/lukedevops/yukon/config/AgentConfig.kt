package io.github.lukedevops.yukon.config

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
    /** Base URL of the collector; the exporter appends `/v1/yukon/{deltas,manifest}`. */
    val collectorEndpoint: String,
    val flushInterval: Duration,
    /** Only types whose name starts with one of these are instrumented; empty means everything. */
    val instrumentedPackagePrefixes: List<String>,
) {
    companion object {
        private const val DEFAULT_ENDPOINT = "http://localhost:4319"
        private val DEFAULT_FLUSH_INTERVAL: Duration = Duration.ofSeconds(60)

        fun parse(agentArgs: String?): AgentConfig {
            val options = parseOptions(agentArgs)
            return AgentConfig(
                serviceName = options["serviceName"] ?: "unknown-service",
                serviceVersion = options["serviceVersion"],
                serviceInstanceId = options["serviceInstanceId"] ?: UUID.randomUUID().toString(),
                environment = options["environment"],
                collectorEndpoint = options["endpoint"] ?: DEFAULT_ENDPOINT,
                flushInterval = options["flushIntervalSeconds"]
                    ?.toLongOrNull()
                    ?.let { Duration.ofSeconds(it) }
                    ?: DEFAULT_FLUSH_INTERVAL,
                instrumentedPackagePrefixes = options["includePackages"]
                    ?.split(";")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList(),
            )
        }

        private fun parseOptions(agentArgs: String?): Map<String, String> {
            if (agentArgs.isNullOrBlank()) return emptyMap()
            return agentArgs.split(",")
                .mapNotNull { pair ->
                    val separator = pair.indexOf('=')
                    if (separator <= 0) null else pair.take(separator).trim() to pair.substring(separator + 1).trim()
                }
                .toMap()
        }
    }
}
