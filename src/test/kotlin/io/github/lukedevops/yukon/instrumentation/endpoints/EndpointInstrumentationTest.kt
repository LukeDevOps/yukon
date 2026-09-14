package io.github.lukedevops.yukon.instrumentation.endpoints

import io.github.lukedevops.yukon.export.EndpointDiscoverySource
import io.github.lukedevops.yukon.instrumentation.BootstrapHolder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import io.github.lukedevops.yukon.registry.EndpointRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import java.lang.instrument.Instrumentation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EndpointInstrumentationTest {
    private var installedInstrumentation: Instrumentation? = null
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedEndpointInstrumentation: EndpointInstrumentation? = null

    private fun frameworkLoader(): FrameworkFixtureClassLoader =
        FrameworkFixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)

    /**
     * Installs [EndpointInstrumentation] for real on the process-wide [Instrumentation], and only
     * then loads a fresh definition of the named fixture class, so the transformer is already
     * registered by the time the class first loads.
     */
    private fun install(
        registry: EndpointRegistry,
        modules: List<EndpointModule>,
        fixtureClassName: String,
    ): Any {
        val instrumentation = ByteBuddyAgent.install()
        installedInstrumentation = instrumentation
        val endpointInstrumentation = EndpointInstrumentation(registry, modules)
        installedEndpointInstrumentation = endpointInstrumentation
        installedTransformer = endpointInstrumentation.install(instrumentation)
        val loader = frameworkLoader()
        val fixtureClass = Class.forName(fixtureClassName, true, loader)
        return fixtureClass.getDeclaredConstructor().newInstance()
    }

    @AfterTest
    fun tearDown() {
        val instrumentation = installedInstrumentation
        val transformer = installedTransformer
        if (instrumentation != null && transformer != null) {
            installedEndpointInstrumentation?.uninstall(instrumentation, transformer)
        }
        installedInstrumentation = null
        installedTransformer = null
        installedEndpointInstrumentation = null
    }

    @Test
    fun `a route registered through addRoute is discovered by REGISTRATION with its handler class attached`() {
        val registry = EndpointRegistry()
        val router = install(registry, listOf(FakeRouterModule()), "com.example.framework.FakeRouter")
        val addRoute = router.javaClass.getMethod("addRoute", String::class.java, String::class.java, Runnable::class.java)
        val handler = Runnable {}

        addRoute.invoke(router, "GET", "/checkout", handler)

        val endpoint = registry.endpoints().single { it.verbatimTemplate == "/checkout" }
        assertEquals(EndpointDiscoverySource.REGISTRATION, endpoint.discoverySource)
        assertEquals("fake-router", endpoint.framework)
        assertEquals(handler.javaClass.name, endpoint.handlerClass)
    }

    @Test
    fun `two dispatches to one route give it hitsTotal 2, and the never-dispatched route nothing`() {
        val registry = EndpointRegistry()
        val router = install(registry, listOf(FakeRouterModule()), "com.example.framework.FakeRouter")
        val addRoute = router.javaClass.getMethod("addRoute", String::class.java, String::class.java, Runnable::class.java)
        val dispatch = router.javaClass.getMethod("dispatch", String::class.java, String::class.java)
        addRoute.invoke(router, "GET", "/hit", Runnable {})
        addRoute.invoke(router, "GET", "/never", Runnable {})

        dispatch.invoke(router, "GET", "/hit")
        dispatch.invoke(router, "GET", "/hit")

        val hitId = registry.endpoints().single { it.verbatimTemplate == "/hit" }.endpointId
        val neverId = registry.endpoints().single { it.verbatimTemplate == "/never" }.endpointId
        val byId = registry.computeDeltas(maxPerBatch = 10).flatMap { it.deltas }.associateBy { it.endpointId }

        assertEquals(2L, byId.getValue(hitId).hitsTotal)
        assertTrue(neverId !in byId)
    }

    @Test
    fun `a route added with addQuietly and then dispatched is discovered by DISPATCH with one hit`() {
        val registry = EndpointRegistry()
        val router = install(registry, listOf(FakeRouterModule()), "com.example.framework.FakeRouter")
        val addQuietly = router.javaClass.getMethod("addQuietly", String::class.java, String::class.java, Runnable::class.java)
        val dispatch = router.javaClass.getMethod("dispatch", String::class.java, String::class.java)
        addQuietly.invoke(router, "GET", "/hidden", Runnable {})

        dispatch.invoke(router, "GET", "/hidden")

        val endpoint = registry.endpoints().single { it.verbatimTemplate == "/hidden" }
        assertEquals(EndpointDiscoverySource.DISPATCH, endpoint.discoverySource)
        val delta = registry.computeDeltas(maxPerBatch = 10).flatMap { it.deltas }.single { it.endpointId == endpoint.endpointId }
        assertEquals(1L, delta.hitsTotal)
    }

    @Test
    fun `a broken module's advice failure disables it, and a second dispatch through it changes nothing`() {
        val registry = EndpointRegistry()
        val broken = install(registry, listOf(BrokenRouterModule()), "com.example.framework.BrokenRouter")
        val addRoute = broken.javaClass.getMethod("addRoute", String::class.java, String::class.java, Runnable::class.java)
        val dispatch = broken.javaClass.getMethod("dispatch", String::class.java, String::class.java)
        addRoute.invoke(broken, "GET", "/broken", Runnable {})

        dispatch.invoke(broken, "GET", "/broken")

        val disabled = registry.disabledModules().single { it.module == "broken-router" }
        assertTrue("simulated framework mismatch" in disabled.reason)

        dispatch.invoke(broken, "GET", "/broken")

        assertEquals(1, registry.disabledModules().count { it.module == "broken-router" })
    }

    @Test
    fun `EndpointModules discover finds the fake module registered as a service`() {
        val discovered = EndpointModules.discover(javaClass.classLoader)

        assertTrue(discovered.any { it.name == "fake-router" })
    }

    @Test
    fun `a module declaring a boot module needing the seam gets a real read edge installed`() {
        val registry = EndpointRegistry()
        install(registry, listOf(FakeRouterModule()), "com.example.framework.FakeRouter")

        val bootModule = ModuleLayer.boot().findModule("java.net.http").get()
        val seamModule = Class.forName(BootstrapHolder.ENDPOINTS_CLASS_NAME, false, null).module

        assertTrue(bootModule.canRead(seamModule))
    }
}
