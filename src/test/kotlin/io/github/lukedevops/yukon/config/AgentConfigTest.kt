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
}
