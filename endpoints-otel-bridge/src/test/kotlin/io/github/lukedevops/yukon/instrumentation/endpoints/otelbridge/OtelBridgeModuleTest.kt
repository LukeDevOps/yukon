package io.github.lukedevops.yukon.instrumentation.endpoints.otelbridge

import net.bytebuddy.description.type.TypeDescription
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves [OtelBridgeModule]'s type matcher and remap rule without needing the real OpenTelemetry
 * javaagent jar on the test classpath: [TypeDescription.Latent] stands in for a class the JVM has
 * never loaded, purely to give the matcher and [remapPrefixesFor] a name to match against. The
 * advice remap itself (bytecode in, bytecode out) is [io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinderTest]'s
 * job; this class only covers which prefixes this module asks for, and for which name.
 */
class OtelBridgeModuleTest {
    @Test
    fun `type matcher matches both the unshaded and shaded HttpServerAttributesExtractor names`() {
        val module = OtelBridgeModule()

        assertTrue(module.typeMatcher().matches(latentType(UNSHADED_EXTRACTOR_NAME)))
        assertTrue(module.typeMatcher().matches(latentType(SHADED_EXTRACTOR_NAME)))
        assertFalse(module.typeMatcher().matches(latentType("com.example.Unrelated")))
    }

    @Test
    fun `remapPrefixesFor is empty for the unshaded name`() {
        assertEquals(emptyMap(), remapPrefixesFor(UNSHADED_EXTRACTOR_NAME))
    }

    @Test
    fun `remapPrefixesFor rewrites every io-opentelemetry prefix to the javaagent's shaded prefix for the shaded name`() {
        val prefixes = remapPrefixesFor(SHADED_EXTRACTOR_NAME)

        assertEquals(
            mapOf(
                "io/opentelemetry/instrumentation/api/" to "io/opentelemetry/javaagent/shaded/instrumentation/api/",
                "io/opentelemetry/context/" to "io/opentelemetry/javaagent/shaded/io/opentelemetry/context/",
                "io/opentelemetry/api/" to "io/opentelemetry/javaagent/shaded/io/opentelemetry/api/",
            ),
            prefixes,
        )
    }

    private fun latentType(name: String): TypeDescription =
        TypeDescription.Latent(name, Modifier.PUBLIC or Modifier.FINAL, TypeDescription.ForLoadedType(Any::class.java).asGenericType())
}
