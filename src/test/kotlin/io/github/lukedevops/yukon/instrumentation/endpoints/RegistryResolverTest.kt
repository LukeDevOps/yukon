package io.github.lukedevops.yukon.instrumentation.endpoints

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints
import io.github.lukedevops.yukon.instrumentation.BootstrapHolder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import io.github.lukedevops.yukon.registry.EndpointRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.none
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistryResolverTest {
    @Test
    fun `a module whose route walk throws is switched off at the seam, not only reported disabled`() {
        BootstrapHolder.install(ByteBuddyAgent.install())
        // The seam's disabled set is process-wide, so the name is unique to this test run.
        val moduleName = "declare-throws-${System.nanoTime()}"
        val module =
            object : EndpointModule {
                override val name: String = moduleName

                override fun typeMatcher(): ElementMatcher<in TypeDescription> = none()

                override fun transform(
                    builder: DynamicType.Builder<*>,
                    typeDescription: TypeDescription,
                    advice: AdviceBinder,
                    classLoader: ClassLoader?,
                ): DynamicType.Builder<*> = builder

                override fun declare(frameworkObject: Any): Unit = throw IllegalStateException("simulated walk failure")
            }
        val registry = EndpointRegistry()
        YukonEndpoints.install(RegistryResolver(registry, listOf(module)))

        YukonEndpoints.declare(moduleName, Any())

        assertTrue(YukonEndpoints.isDisabled(moduleName), "a half-declared module must stop counting, not keep going")
        val disabled = registry.disabledModules().single()
        assertEquals(moduleName, disabled.module)
        assertTrue("simulated walk failure" in disabled.reason)
        assertNull(
            YukonEndpoints.recordDispatch(moduleName, "key", "GET", "/after-failure", null, null),
            "dispatch through the switched-off module must count nothing",
        )
        assertTrue(registry.endpoints().isEmpty())
    }
}
