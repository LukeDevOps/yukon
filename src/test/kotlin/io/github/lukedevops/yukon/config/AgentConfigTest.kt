package io.github.lukedevops.yukon.config

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AgentConfigTest {
    @Test
    fun `null args fall back to defaults`() {
        val config = AgentConfig.parse(null)

        assertEquals("unknown-service", config.serviceName)
        assertEquals(null, config.serviceVersion)
        assertNotNull(config.serviceInstanceId)
        assertEquals(Duration.ofSeconds(60), config.flushInterval)
        assertEquals("http://localhost:4319", config.collectorEndpoint)
        assertEquals(emptyList(), config.instrumentedPackagePrefixes)
        assertEquals(false, config.staticBaselineEnabled)
    }

    @Test
    fun `staticBaselineEnabled defaults to false and can be opted into`() {
        assertEquals(false, AgentConfig.parse("serviceName=checkout").staticBaselineEnabled)
        assertEquals(true, AgentConfig.parse("staticBaselineEnabled=true").staticBaselineEnabled)
    }

    @Test
    fun `staticBaselineEnabled is case-insensitive and treats any non-'true' value as false`() {
        assertEquals(true, AgentConfig.parse("staticBaselineEnabled=TRUE").staticBaselineEnabled)
        assertEquals(false, AgentConfig.parse("staticBaselineEnabled=yes").staticBaselineEnabled)
    }

    @Test
    fun `parses comma-separated key=value pairs`() {
        val config =
            AgentConfig.parse(
                "serviceName=checkout,serviceVersion=1.2.3,environment=prod," +
                    "endpoint=https://collector.example.com,flushIntervalSeconds=30",
            )

        assertEquals("checkout", config.serviceName)
        assertEquals("1.2.3", config.serviceVersion)
        assertEquals("prod", config.environment)
        assertEquals("https://collector.example.com", config.collectorEndpoint)
        assertEquals(Duration.ofSeconds(30), config.flushInterval)
    }

    @Test
    fun `blank args string behaves like null`() {
        val config = AgentConfig.parse("   ")

        assertEquals("unknown-service", config.serviceName)
    }

    @Test
    fun `malformed pairs without an equals sign are ignored`() {
        val config = AgentConfig.parse("serviceName=checkout,garbage,environment=prod")

        assertEquals("checkout", config.serviceName)
        assertEquals("prod", config.environment)
    }

    @Test
    fun `explicit serviceInstanceId is honored`() {
        val config = AgentConfig.parse("serviceInstanceId=instance-42")

        assertEquals("instance-42", config.serviceInstanceId)
    }

    @Test
    fun `includePackages splits on semicolons and trims whitespace`() {
        val config = AgentConfig.parse("includePackages=com.acme ; com.acme.internal;com.other")

        assertEquals(listOf("com.acme", "com.acme.internal", "com.other"), config.instrumentedPackagePrefixes)
    }

    @Test
    fun `a zero flushIntervalSeconds falls back to the default instead of producing an invalid schedule`() {
        // ExportScheduler.start() passes flushInterval straight into scheduleAtFixedRate. That
        // call throws for a non-positive period. It runs inside Agent.premain, so an uncaught
        // exception there aborts the whole target JVM. A bad flag value must never reach a
        // non-positive Duration.
        val config = AgentConfig.parse("flushIntervalSeconds=0")

        assertEquals(Duration.ofSeconds(60), config.flushInterval)
    }

    @Test
    fun `a negative flushIntervalSeconds falls back to the default`() {
        val config = AgentConfig.parse("flushIntervalSeconds=-5")

        assertEquals(Duration.ofSeconds(60), config.flushInterval)
    }

    @Test
    fun `a non-numeric flushIntervalSeconds falls back to the default`() {
        val config = AgentConfig.parse("flushIntervalSeconds=soon")

        assertEquals(Duration.ofSeconds(60), config.flushInterval)
    }

    @Test
    fun `a trailing slash on the endpoint is dropped so request paths do not get a double slash`() {
        assertEquals("https://collector.example.com", AgentConfig.parse("endpoint=https://collector.example.com/").collectorEndpoint)
        assertEquals("http://host:4319/base", AgentConfig.parse("endpoint=http://host:4319/base//").collectorEndpoint)
    }

    @Test
    fun `an endpoint that is not an absolute http(s) URL falls back to the default`() {
        // Left as is, URI.create would throw on every attempt of every flush.
        assertEquals("http://localhost:4319", AgentConfig.parse("endpoint=not a url").collectorEndpoint)
        assertEquals("http://localhost:4319", AgentConfig.parse("endpoint=ftp://collector.example.com").collectorEndpoint)
        assertEquals("http://localhost:4319", AgentConfig.parse("endpoint=/v1/yukon").collectorEndpoint)
    }

    @Test
    fun `an unknown option key is ignored without disturbing the known ones`() {
        val config = AgentConfig.parse("serviceName=checkout,includePackage=com.acme")

        assertEquals("checkout", config.serviceName)
        assertEquals(emptyList(), config.instrumentedPackagePrefixes, "the typo'd key must not silently act as includePackages")
    }

    @Test
    fun `a trailing dot on an includePackages prefix is dropped`() {
        val config = AgentConfig.parse("includePackages=com.acme.;com.other")

        assertEquals(listOf("com.acme", "com.other"), config.instrumentedPackagePrefixes)
    }
}
