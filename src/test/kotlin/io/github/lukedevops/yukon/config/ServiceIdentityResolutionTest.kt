package io.github.lukedevops.yukon.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The resolution order ADR 0045 gives the service name, the namespace and the environment: Yukon's
 * own three sources, then OpenTelemetry's own settings as its Java agent resolves them, then, for
 * the name only, detection and the default.
 */
class ServiceIdentityResolutionTest {
    private class Sources {
        val options = mutableListOf<String>()
        val properties = mutableMapOf<String, String>()
        val env = mutableMapOf<String, String>()
        var detected: String? = null

        fun parse(): AgentConfig =
            AgentConfig.parse(
                options.joinToString(",").ifEmpty { null },
                env = env::get,
                systemProperties = properties::get,
                detectServiceName = { detected },
            )
    }

    /**
     * One source in a chain. [setBlank] makes the source blank so that it falls through. For a
     * resource attribute that means a blank list, since a list that is set hides the one below it.
     */
    private class Level(
        val name: String,
        val set: (Sources, String) -> Unit,
        val setBlank: (Sources) -> Unit = { set(it, "  ") },
    )

    private fun option(key: String) = Level("option $key", set = { s, v -> s.options += "$key=$v" })

    private fun property(key: String) = Level("property $key", set = { s, v -> s.properties[key] = v })

    private fun envVar(key: String) = Level("env $key", set = { s, v -> s.env[key] = v })

    private fun propertyAttribute(attribute: String) =
        Level(
            "otel.resource.attributes $attribute",
            { s, v -> s.properties.appendAttribute("otel.resource.attributes", attribute, v) },
            { s -> s.properties["otel.resource.attributes"] = "  " },
        )

    private fun envAttribute(attribute: String) =
        Level(
            "OTEL_RESOURCE_ATTRIBUTES $attribute",
            { s, v -> s.env.appendAttribute("OTEL_RESOURCE_ATTRIBUTES", attribute, v) },
            { s -> s.env["OTEL_RESOURCE_ATTRIBUTES"] = "  " },
        )

    private fun MutableMap<String, String>.appendAttribute(
        key: String,
        attribute: String,
        value: String,
    ) {
        this[key] = listOfNotNull(this[key], "$attribute=$value").joinToString(",")
    }

    private val detection = Level("detection", set = { s, v -> s.detected = v })

    private fun valueFor(level: Level) = "from-" + level.name.replace(' ', '-')

    /**
     * For each level, sets it and every level below it and expects its value. Then sets every
     * level above it to a blank value and expects the blank ones to fall through to it. With every
     * level blank, expects [fallback].
     */
    private fun assertChain(
        levels: List<Level>,
        read: (AgentConfig) -> String?,
        fallback: String?,
    ) {
        for ((winner, level) in levels.withIndex()) {
            val lowerSet = Sources()
            for (lower in levels.drop(winner)) lower.set(lowerSet, valueFor(lower))
            assertEquals(valueFor(level), read(lowerSet.parse()), "${level.name} should win over every level below it")

            val blankAbove = Sources()
            for (above in levels.take(winner)) above.setBlank(blankAbove)
            for (lower in levels.drop(winner)) lower.set(blankAbove, valueFor(lower))
            assertEquals(valueFor(level), read(blankAbove.parse()), "a blank value above ${level.name} should fall through to it")
        }
        val allBlank = Sources()
        for (level in levels) level.setBlank(allBlank)
        assertEquals(fallback, read(allBlank.parse()), "every level blank")
    }

    @Test
    fun `the service name resolves through Yukon, OpenTelemetry, detection and the default in order`() {
        assertChain(
            listOf(
                option("serviceName"),
                property("yukon.service.name"),
                envVar("YUKON_SERVICE_NAME"),
                property("otel.service.name"),
                envVar("OTEL_SERVICE_NAME"),
                propertyAttribute("service.name"),
                envAttribute("service.name"),
                detection,
            ),
            read = { it.serviceName },
            fallback = "unknown_service:java",
        )
    }

    @Test
    fun `the namespace resolves through Yukon then OpenTelemetry and has no default`() {
        assertChain(
            listOf(
                option("serviceNamespace"),
                property("yukon.service.namespace"),
                envVar("YUKON_SERVICE_NAMESPACE"),
                propertyAttribute("service.namespace"),
                envAttribute("service.namespace"),
            ),
            read = { it.serviceNamespace },
            fallback = null,
        )
    }

    @Test
    fun `the environment resolves through Yukon then OpenTelemetry and has no default`() {
        assertChain(
            listOf(
                option("environment"),
                property("yukon.environment"),
                envVar("YUKON_ENVIRONMENT"),
                propertyAttribute("deployment.environment.name"),
                envAttribute("deployment.environment.name"),
            ),
            read = { it.environment },
            fallback = null,
        )
    }

    @Test
    fun `a dot namespace from a Yukon source falls through to the next source`() {
        val fromProperty =
            AgentConfig.parse(
                "serviceNamespace=.",
                env = { null },
                systemProperties = mapOf("yukon.service.namespace" to "shop")::get,
                detectServiceName = { null },
            )
        assertEquals("shop", fromProperty.serviceNamespace)

        val fromOtel =
            AgentConfig.parse(
                null,
                env = mapOf("YUKON_SERVICE_NAMESPACE" to " .. ", "OTEL_RESOURCE_ATTRIBUTES" to "service.namespace=shop")::get,
                systemProperties = { null },
                detectServiceName = { null },
            )
        assertEquals("shop", fromOtel.serviceNamespace)
    }

    @Test
    fun `a dot namespace attribute leaves the service in the unspecified namespace`() {
        val config =
            AgentConfig.parse(
                null,
                env = mapOf("OTEL_RESOURCE_ATTRIBUTES" to "service.namespace=%2E%2E")::get,
                systemProperties = { null },
                detectServiceName = { null },
            )
        assertEquals(null, config.serviceNamespace)
    }

    @Test
    fun `a dot service name falls through to the next source`() {
        val fromAttribute =
            AgentConfig.parse(
                "serviceName=..",
                env = mapOf("OTEL_SERVICE_NAME" to ".", "OTEL_RESOURCE_ATTRIBUTES" to "service.name=checkout")::get,
                systemProperties = { null },
                detectServiceName = { null },
            )
        assertEquals("checkout", fromAttribute.serviceName)

        val fromDefault = AgentConfig.parse(null, env = { null }, systemProperties = { null }, detectServiceName = { ".." })
        assertEquals("unknown_service:java", fromDefault.serviceName)
    }

    @Test
    fun `dots inside a name are kept`() {
        val config =
            AgentConfig.parse(
                "serviceName=checkout.v2,serviceNamespace=...",
                env = { null },
                systemProperties = { null },
                detectServiceName = { null },
            )
        assertEquals("checkout.v2", config.serviceName)
        assertEquals("...", config.serviceNamespace)
    }

    @Test
    fun `deployment environment name wins over the older deployment environment key in one list`() {
        val both = mapOf("OTEL_RESOURCE_ATTRIBUTES" to "deployment.environment=old,deployment.environment.name=new")
        assertEquals("new", AgentConfig.parse(null, env = both::get, systemProperties = { null }, detectServiceName = { null }).environment)

        val olderOnly = mapOf("OTEL_RESOURCE_ATTRIBUTES" to "deployment.environment=old")
        assertEquals("old", AgentConfig.parse(null, env = olderOnly::get, systemProperties = { null }, detectServiceName = { null }).environment)

        val blankNewer = mapOf("OTEL_RESOURCE_ATTRIBUTES" to "deployment.environment.name=,deployment.environment=old")
        assertEquals("old", AgentConfig.parse(null, env = blankNewer::get, systemProperties = { null }, detectServiceName = { null }).environment)
    }

    @Test
    fun `a set otel resource attributes property hides OTEL_RESOURCE_ATTRIBUTES entirely`() {
        val config =
            AgentConfig.parse(
                null,
                env =
                    mapOf(
                        "OTEL_RESOURCE_ATTRIBUTES" to "service.name=from-env,service.namespace=ns-env,deployment.environment.name=env-env",
                    )::get,
                systemProperties = mapOf("otel.resource.attributes" to "service.namespace=ns-property")::get,
                detectServiceName = { "detected" },
            )

        assertEquals("ns-property", config.serviceNamespace)
        assertEquals(null, config.environment)
        assertEquals("detected", config.serviceName)
    }

    @Test
    fun `OTEL_SERVICE_NAME wins over a service name in the otel resource attributes property`() {
        val config =
            AgentConfig.parse(
                null,
                env = mapOf("OTEL_SERVICE_NAME" to "from-env")::get,
                systemProperties = mapOf("otel.resource.attributes" to "service.name=from-property")::get,
                detectServiceName = { null },
            )

        assertEquals("from-env", config.serviceName)
    }

    @Test
    fun `a malformed otel resource attributes property still hides OTEL_RESOURCE_ATTRIBUTES`() {
        val config =
            AgentConfig.parse(
                null,
                env = mapOf("OTEL_RESOURCE_ATTRIBUTES" to "service.namespace=ns-env")::get,
                systemProperties = mapOf("otel.resource.attributes" to "service.namespace=ns-property,garbage")::get,
                detectServiceName = { null },
            )

        assertEquals(null, config.serviceNamespace)
    }

    @Test
    fun `the older environment key in system properties wins over the newer key in the environment`() {
        val config =
            AgentConfig.parse(
                null,
                env = mapOf("OTEL_RESOURCE_ATTRIBUTES" to "deployment.environment.name=from-env")::get,
                systemProperties = mapOf("otel.resource.attributes" to "deployment.environment=from-property")::get,
                detectServiceName = { null },
            )

        assertEquals("from-property", config.environment)
    }

    @Test
    fun `OpenTelemetry values are trimmed`() {
        val config =
            AgentConfig.parse(
                null,
                env = mapOf("OTEL_SERVICE_NAME" to "  checkout  ", "OTEL_RESOURCE_ATTRIBUTES" to "service.namespace = shop ")::get,
                systemProperties = { null },
                detectServiceName = { null },
            )

        assertEquals("checkout", config.serviceName)
        assertEquals("shop", config.serviceNamespace)
    }

    @Test
    fun `a detected name is trimmed, and a blank one falls through to the default`() {
        val none: (String) -> String? = { null }
        assertEquals("app", AgentConfig.parse(null, env = none, systemProperties = none, detectServiceName = { "  app " }).serviceName)
        assertEquals(
            "unknown_service:java",
            AgentConfig.parse(null, env = none, systemProperties = none, detectServiceName = { "  " }).serviceName,
        )
    }

    @Test
    fun `detection does not run when any setting names the service`() {
        val config =
            AgentConfig.parse(
                null,
                env = mapOf("OTEL_RESOURCE_ATTRIBUTES" to "service.name=checkout")::get,
                systemProperties = { null },
                detectServiceName = { fail("detection ran although OTEL_RESOURCE_ATTRIBUTES names the service") },
            )

        assertEquals("checkout", config.serviceName)
    }

    @Test
    fun `detection that throws falls through to the default`() {
        val config =
            AgentConfig.parse(null, env = { null }, systemProperties = { null }, detectServiceName = { throw IllegalStateException("boom") })

        assertEquals("unknown_service:java", config.serviceName)
    }

    @Test
    fun `serviceNamespace derives its property and environment variable names by ADR 0016's rule`() {
        assertEquals("yukon.service.namespace", OptionNames.systemProperty("serviceNamespace"))
        assertEquals("YUKON_SERVICE_NAMESPACE", OptionNames.environmentVariable("serviceNamespace"))
    }
}
