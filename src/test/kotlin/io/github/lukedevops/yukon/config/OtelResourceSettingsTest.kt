package io.github.lukedevops.yukon.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OtelResourceSettingsTest {
    private fun parse(raw: String?) = OtelResourceSettings.parseAttributes(raw, "OTEL_RESOURCE_ATTRIBUTES")

    @Test
    fun `a list of key=value pairs parses in order`() {
        assertEquals(
            mapOf("service.name" to "checkout", "service.namespace" to "shop"),
            parse("service.name=checkout,service.namespace=shop"),
        )
    }

    @Test
    fun `null, empty and blank lists give no attributes`() {
        assertEquals(emptyMap(), parse(null))
        assertEquals(emptyMap(), parse(""))
        assertEquals(emptyMap(), parse("  "))
    }

    @Test
    fun `whitespace around keys, values and pairs is trimmed`() {
        assertEquals(
            mapOf("service.name" to "checkout", "service.namespace" to "shop"),
            parse("  service.name =  checkout , service.namespace= shop  "),
        )
    }

    @Test
    fun `values are percent-decoded, with a comma, an equals sign, a space and multi-byte UTF-8`() {
        assertEquals(
            mapOf("service.name" to "a,b=c d", "service.namespace" to "café"),
            parse("service.name=a%2Cb%3Dc%20d,service.namespace=caf%C3%A9"),
        )
    }

    @Test
    fun `a plus sign stays a plus sign and a percent sign without two hex digits stays as it is`() {
        assertEquals(mapOf("k" to "a+b"), parse("k=a+b"))
        assertEquals(mapOf("k" to "100%"), parse("k=100%"))
        assertEquals(mapOf("k" to "%2G%4"), parse("k=%2G%4"))
    }

    @Test
    fun `a value that decodes to spaces only is dropped`() {
        assertEquals(emptyMap(), parse("service.name=%20%20"))
    }

    @Test
    fun `a value may hold an unencoded equals sign after the first one`() {
        assertEquals(mapOf("k" to "a=b"), parse("k=a=b"))
    }

    @Test
    fun `a pair with no equals sign discards the whole list`() {
        assertEquals(emptyMap(), parse("service.name=checkout,garbage,service.namespace=shop"))
    }

    @Test
    fun `a pair with an empty key discards the whole list`() {
        assertEquals(emptyMap(), parse("service.name=checkout,=orphan"))
        assertEquals(emptyMap(), parse("service.name=checkout, =blank-key"))
    }

    @Test
    fun `empty pairs between commas are not malformed`() {
        assertEquals(mapOf("service.name" to "checkout"), parse(",service.name=checkout,,"))
    }

    @Test
    fun `a pair with an empty value is dropped`() {
        assertEquals(mapOf("service.namespace" to "shop"), parse("service.name=,service.namespace=shop"))
    }

    @Test
    fun `when a key repeats, the last pair wins`() {
        assertEquals(mapOf("service.name" to "second"), parse("service.name=first,service.name=second"))
    }

    @Test
    fun `a repeated key with an empty last value keeps the earlier value`() {
        assertEquals(mapOf("service.name" to "first"), parse("service.name=first,service.name="))
    }

    private fun resolve(
        properties: Map<String, String> = emptyMap(),
        env: Map<String, String> = emptyMap(),
    ) = OtelResourceSettings.resolve(properties::get, env::get)

    @Test
    fun `the service name setting wins over the service name attribute`() {
        val settings = resolve(env = mapOf("OTEL_SERVICE_NAME" to "dedicated", "OTEL_RESOURCE_ATTRIBUTES" to "service.name=attribute"))

        assertEquals("dedicated", settings.serviceName)
    }

    @Test
    fun `OTEL_SERVICE_NAME wins over a service name in the otel resource attributes property`() {
        val settings =
            resolve(
                properties = mapOf("otel.resource.attributes" to "service.name=from-property-attribute"),
                env = mapOf("OTEL_SERVICE_NAME" to "from-env-setting"),
            )

        assertEquals("from-env-setting", settings.serviceName)
    }

    @Test
    fun `the otel service name property wins over OTEL_SERVICE_NAME`() {
        assertEquals(
            "from-property",
            resolve(properties = mapOf("otel.service.name" to "from-property"), env = mapOf("OTEL_SERVICE_NAME" to "from-env")).serviceName,
        )
    }

    @Test
    fun `a set otel resource attributes property hides every key of OTEL_RESOURCE_ATTRIBUTES`() {
        val settings =
            resolve(
                properties = mapOf("otel.resource.attributes" to "service.namespace=ns-property"),
                env = mapOf("OTEL_RESOURCE_ATTRIBUTES" to "service.name=name-env,service.namespace=ns-env,deployment.environment=env-env"),
            )

        assertEquals("ns-property", settings.serviceNamespace)
        assertNull(settings.serviceName)
        assertNull(settings.environment)
    }

    @Test
    fun `a blank property falls through to its environment variable`() {
        val settings =
            resolve(
                properties = mapOf("otel.service.name" to "  ", "otel.resource.attributes" to " "),
                env = mapOf("OTEL_SERVICE_NAME" to "name-env", "OTEL_RESOURCE_ATTRIBUTES" to "service.namespace=ns-env"),
            )

        assertEquals("name-env", settings.serviceName)
        assertEquals("ns-env", settings.serviceNamespace)
    }

    @Test
    fun `a malformed property list is discarded and still hides OTEL_RESOURCE_ATTRIBUTES`() {
        val settings =
            resolve(
                properties = mapOf("otel.resource.attributes" to "service.namespace=ns-property,garbage"),
                env = mapOf("OTEL_RESOURCE_ATTRIBUTES" to "service.namespace=ns-env"),
            )

        assertNull(settings.serviceNamespace)
    }

    @Test
    fun `the environment reads deployment environment name, else the older deployment environment`() {
        assertEquals("env-p", resolve(properties = mapOf("otel.resource.attributes" to "deployment.environment.name=env-p")).environment)
        assertEquals("env-e", resolve(env = mapOf("OTEL_RESOURCE_ATTRIBUTES" to "deployment.environment=env-e")).environment)
    }
}
