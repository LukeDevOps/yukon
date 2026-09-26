package io.github.lukedevops.yukon.config

import java.io.ByteArrayOutputStream
import java.lang.System.Logger.Level

/**
 * The resource values OpenTelemetry's own settings give, resolved as its Java agent resolves them.
 * [AgentConfig] falls back to these when Yukon's own sources name no value. See ADR 0045.
 *
 * [serviceNameSetting] is the `otel.service.name` setting. [attributes] is the parsed
 * `otel.resource.attributes` setting. Every value in both is trimmed and not blank.
 */
internal class OtelResourceSettings(
    private val serviceNameSetting: String?,
    private val attributes: Map<String, String>,
) {
    /**
     * The service name: the `otel.service.name` setting, else the `service.name` attribute. A value
     * that [ServiceIdentityValues.usable] skips falls through to the next one.
     */
    val serviceName: String? =
        ServiceIdentityValues.usable(serviceNameSetting, "the otel.service.name setting")
            ?: ServiceIdentityValues.usable(attributes["service.name"], "service.name in the resource attributes")

    /** The `service.namespace` attribute, unless [ServiceIdentityValues.usable] skips it. */
    val serviceNamespace: String? =
        ServiceIdentityValues.usable(attributes["service.namespace"], "service.namespace in the resource attributes")

    /** The `deployment.environment.name` attribute, else the older `deployment.environment`. */
    val environment: String? get() = attributes["deployment.environment.name"] ?: attributes["deployment.environment"]

    companion object {
        private val log = System.getLogger(OtelResourceSettings::class.java.name)

        /**
         * Resolves each of `otel.service.name` and `otel.resource.attributes` as one setting. Each
         * comes whole from its system property, else from its environment variable
         * (`OTEL_SERVICE_NAME`, `OTEL_RESOURCE_ATTRIBUTES`). So a set `otel.resource.attributes`
         * property hides `OTEL_RESOURCE_ATTRIBUTES` entirely, and `OTEL_SERVICE_NAME` wins over a
         * `service.name` in the `otel.resource.attributes` property.
         *
         * A blank system property counts as unset and falls through to the environment variable,
         * as a blank value does everywhere else in [AgentConfig].
         */
        fun resolve(
            systemProperties: (String) -> String?,
            env: (String) -> String?,
        ): OtelResourceSettings {
            val serviceName = nonBlank(systemProperties("otel.service.name")) ?: nonBlank(env("OTEL_SERVICE_NAME"))
            val attributesFromProperty = nonBlank(systemProperties("otel.resource.attributes"))
            val attributes =
                if (attributesFromProperty != null) {
                    parseAttributes(attributesFromProperty, "the otel.resource.attributes system property")
                } else {
                    parseAttributes(env("OTEL_RESOURCE_ATTRIBUTES"), "OTEL_RESOURCE_ATTRIBUTES")
                }
            return OtelResourceSettings(serviceName, attributes)
        }

        private fun nonBlank(raw: String?): String? = raw?.trim()?.ifBlank { null }

        /**
         * Parses a resource-attributes list, `key1=value1,key2=value2`, as OpenTelemetry's Java SDK
         * does. It splits on commas and then on the first `=` of each pair. It trims each key and
         * value, and then percent-decodes the value by [percentDecode]. A pair with an empty
         * value is dropped. When a key repeats, the last pair wins.
         *
         * If any pair has no `=` or an empty key, the whole list is discarded with one WARNING that
         * names [source], as the OpenTelemetry specification says.
         */
        fun parseAttributes(
            raw: String?,
            source: String,
        ): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            val attributes = LinkedHashMap<String, String>()
            for (entry in raw.split(',')) {
                val pair = entry.trim()
                if (pair.isEmpty()) continue
                val separator = pair.indexOf('=')
                val key = if (separator < 0) "" else pair.take(separator).trim()
                if (key.isEmpty()) {
                    log.log(
                        Level.WARNING,
                        "yukon: ignoring all of $source, since its entry '$pair' is not key=value; " +
                            "no service name, namespace or environment is read from it",
                    )
                    return emptyMap()
                }
                val value = percentDecode(pair.substring(separator + 1).trim()).trim()
                if (value.isEmpty()) continue
                attributes[key] = value
            }
            return attributes
        }

        /**
         * Decodes `%XY` escapes as UTF-8 bytes, per the W3C Baggage rules the OpenTelemetry
         * specification points to. A `+` stays a `+`, unlike in form decoding. A `%` that is not
         * followed by two hex digits stays as it is.
         */
        fun percentDecode(value: String): String {
            if (value.indexOf('%') < 0) return value
            val decoded = StringBuilder(value.length)
            val bytes = ByteArrayOutputStream()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                val high = if (c == '%' && i + 2 < value.length) Character.digit(value[i + 1], 16) else -1
                val low = if (high >= 0) Character.digit(value[i + 2], 16) else -1
                if (low >= 0) {
                    bytes.write((high shl 4) + low)
                    i += 3
                    continue
                }
                if (bytes.size() > 0) {
                    decoded.append(bytes.toString(Charsets.UTF_8))
                    bytes.reset()
                }
                decoded.append(c)
                i++
            }
            if (bytes.size() > 0) decoded.append(bytes.toString(Charsets.UTF_8))
            return decoded.toString()
        }
    }
}
