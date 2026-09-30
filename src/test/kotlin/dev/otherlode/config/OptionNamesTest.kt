package dev.otherlode.config

import kotlin.test.Test
import kotlin.test.assertEquals

class OptionNamesTest {
    @Test
    fun `system property name is derived from the camelCase option name`() {
        assertEquals("otherlode.service.name", OptionNames.systemProperty("serviceName"))
        assertEquals("otherlode.flush.interval.seconds", OptionNames.systemProperty("flushIntervalSeconds"))
        assertEquals("otherlode.static.baseline.enabled", OptionNames.systemProperty("staticBaselineEnabled"))
        assertEquals("otherlode.auth.token", OptionNames.systemProperty("authToken"))
        assertEquals("otherlode.include.packages", OptionNames.systemProperty("includePackages"))
        assertEquals("otherlode.exclude.packages", OptionNames.systemProperty("excludePackages"))
        assertEquals("otherlode.enabled", OptionNames.systemProperty("enabled"))
        assertEquals("otherlode.otel.bridge.enabled", OptionNames.systemProperty("otelBridgeEnabled"))
    }

    @Test
    fun `environment variable name is derived from the camelCase option name`() {
        assertEquals("OTHERLODE_SERVICE_NAME", OptionNames.environmentVariable("serviceName"))
        assertEquals("OTHERLODE_FLUSH_INTERVAL_SECONDS", OptionNames.environmentVariable("flushIntervalSeconds"))
        assertEquals("OTHERLODE_STATIC_BASELINE_ENABLED", OptionNames.environmentVariable("staticBaselineEnabled"))
        assertEquals(
            "OTHERLODE_AUTH_TOKEN",
            OptionNames.environmentVariable("authToken"),
            "the existing name must fall out of the rule, not be special-cased",
        )
        assertEquals("OTHERLODE_INCLUDE_PACKAGES", OptionNames.environmentVariable("includePackages"))
        assertEquals("OTHERLODE_EXCLUDE_PACKAGES", OptionNames.environmentVariable("excludePackages"))
        assertEquals("OTHERLODE_ENABLED", OptionNames.environmentVariable("enabled"))
        assertEquals("OTHERLODE_OTEL_BRIDGE_ENABLED", OptionNames.environmentVariable("otelBridgeEnabled"))
    }
}
