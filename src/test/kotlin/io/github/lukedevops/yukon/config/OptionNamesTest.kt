package io.github.lukedevops.yukon.config

import kotlin.test.Test
import kotlin.test.assertEquals

class OptionNamesTest {
    @Test
    fun `system property name is derived from the camelCase option name`() {
        assertEquals("yukon.service.name", OptionNames.systemProperty("serviceName"))
        assertEquals("yukon.flush.interval.seconds", OptionNames.systemProperty("flushIntervalSeconds"))
        assertEquals("yukon.static.baseline.enabled", OptionNames.systemProperty("staticBaselineEnabled"))
        assertEquals("yukon.auth.token", OptionNames.systemProperty("authToken"))
        assertEquals("yukon.include.packages", OptionNames.systemProperty("includePackages"))
        assertEquals("yukon.exclude.packages", OptionNames.systemProperty("excludePackages"))
        assertEquals("yukon.enabled", OptionNames.systemProperty("enabled"))
        assertEquals("yukon.otel.bridge.enabled", OptionNames.systemProperty("otelBridgeEnabled"))
    }

    @Test
    fun `environment variable name is derived from the camelCase option name`() {
        assertEquals("YUKON_SERVICE_NAME", OptionNames.environmentVariable("serviceName"))
        assertEquals("YUKON_FLUSH_INTERVAL_SECONDS", OptionNames.environmentVariable("flushIntervalSeconds"))
        assertEquals("YUKON_STATIC_BASELINE_ENABLED", OptionNames.environmentVariable("staticBaselineEnabled"))
        assertEquals(
            "YUKON_AUTH_TOKEN",
            OptionNames.environmentVariable("authToken"),
            "the existing name must fall out of the rule, not be special-cased",
        )
        assertEquals("YUKON_INCLUDE_PACKAGES", OptionNames.environmentVariable("includePackages"))
        assertEquals("YUKON_EXCLUDE_PACKAGES", OptionNames.environmentVariable("excludePackages"))
        assertEquals("YUKON_ENABLED", OptionNames.environmentVariable("enabled"))
        assertEquals("YUKON_OTEL_BRIDGE_ENABLED", OptionNames.environmentVariable("otelBridgeEnabled"))
    }
}
