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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistryResolverTest {
    @Test
    fun `attachHandler with a null handler class does not erase a previously attached join`() {
        BootstrapHolder.install(ByteBuddyAgent.install())
        val moduleName = "attach-null-${System.nanoTime()}"
        val registry = EndpointRegistry()
        YukonEndpoints.install(RegistryResolver(registry, pendingDeclarations = PendingDeclarations()))

        val key = "attach-null-key-${System.nanoTime()}"
        val entry =
            YukonEndpoints.register(moduleName, key, "GET", "/health", null, "com.example.HealthHandler", "handle", "()V")
        assertNotNull(entry, "register must resolve an entry once a resolver is installed")

        // A dispatch through a hidden class (a Java or Kotlin SAM lambda) has nothing to join with, but
        // must never overwrite a real join a registration hook already recorded.
        YukonEndpoints.attachHandler(moduleName, entry, null, null, null)

        val endpoint = registry.endpoints().single { it.verbatimTemplate == "/health" }
        assertEquals("com.example.HealthHandler", endpoint.handlerClass)
        assertEquals("handle", endpoint.handlerMethod)
        assertEquals("()V", endpoint.handlerDescriptor)
    }

    @Test
    fun `register with a null handler class does not erase a previously attached join for the same identity`() {
        BootstrapHolder.install(ByteBuddyAgent.install())
        val moduleName = "register-null-${System.nanoTime()}"
        val registry = EndpointRegistry()
        YukonEndpoints.install(RegistryResolver(registry, pendingDeclarations = PendingDeclarations()))

        val firstKey = "register-null-key-1-${System.nanoTime()}"
        YukonEndpoints.register(moduleName, firstKey, "GET", "/health", null, "com.example.HealthHandler", "handle", "()V")

        // A second registration hook for the same (verb, template) identity that only has a hidden class
        // to offer must not blank out the join the first registration already recorded.
        val secondKey = "register-null-key-2-${System.nanoTime()}"
        YukonEndpoints.register(moduleName, secondKey, "GET", "/health", null, null, null, null)

        val endpoint = registry.endpoints().single { it.verbatimTemplate == "/health" }
        assertEquals("com.example.HealthHandler", endpoint.handlerClass)
        assertEquals("handle", endpoint.handlerMethod)
        assertEquals("()V", endpoint.handlerDescriptor)
    }

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
        YukonEndpoints.install(RegistryResolver(registry, listOf(module), PendingDeclarations()))

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

    @Test
    fun `an endpoint declared during a transform reaches the registry only once that transform commits`() {
        val registry = EndpointRegistry()
        val pending = PendingDeclarations()
        val resolver = RegistryResolver(registry, emptyList(), pending)

        pending.begin()
        assertNull(
            resolver.register("key-1", "fake", "GET", "/staged", null, "com.example.Handler", "handle", "()V"),
            "a staged declaration answers null rather than an entry",
        )
        assertEquals(0, registry.computeManifestEntries(100).sumOf { it.endpoints.size }, "nothing is declared while staged")

        pending.commit()
        val declared = registry.computeManifestEntries(100).flatMap { it.endpoints }
        assertEquals(1, declared.size, "the commit declares what the transform staged")
        assertEquals("/staged", declared.single().routeTemplate)
    }

    @Test
    fun `an endpoint declared by a transform that never commits is dropped`() {
        val registry = EndpointRegistry()
        val pending = PendingDeclarations()
        val resolver = RegistryResolver(registry, emptyList(), pending)

        pending.begin()
        resolver.register("key-1", "fake", "GET", "/dropped", null, null, null, null)
        // What onComplete does for a transform that failed: the class runs without advice, so an
        // endpoint declared for it would sit at zero dispatches and read as never called.
        pending.discard()

        assertEquals(0, registry.computeManifestEntries(100).sumOf { it.endpoints.size })
        assertEquals(0, pending.pendingCount(), "the thread holds nothing afterwards")
    }

    @Test
    fun `an endpoint declared outside any transform is registered straight away`() {
        val registry = EndpointRegistry()
        val resolver = RegistryResolver(registry, emptyList(), PendingDeclarations())

        // The runtime path: a module walking a framework object once the application built it.
        assertNotNull(resolver.register("key-1", "fake", "GET", "/live", null, null, null, null))
        assertEquals(1, registry.computeManifestEntries(100).sumOf { it.endpoints.size })
    }

    @Test
    fun `declare for a name no module registered under is ignored, and is not a module failure`() {
        val registry = EndpointRegistry()
        val resolver = RegistryResolver(registry, emptyList(), PendingDeclarations())

        resolver.declare("nobody", Any())
        resolver.declare("nobody", Any())

        assertTrue(registry.endpoints().isEmpty())
        assertTrue(registry.disabledModules().isEmpty())
    }

    @Test
    fun `a staged declaration that throws on commit does not stop the ones after it`() {
        val pending = PendingDeclarations()
        pending.begin()
        var ranAfterTheFailure = false
        pending.stage { throw IllegalStateException("bad route") }
        pending.stage { ranAfterTheFailure = true }

        pending.commit()

        assertTrue(ranAfterTheFailure)
        assertEquals(0, pending.pendingCount())
    }
}
