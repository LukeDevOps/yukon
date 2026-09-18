package io.github.lukedevops.yukon.instrumentation.endpoints

import io.github.lukedevops.yukon.export.EndpointDiscoverySource
import io.github.lukedevops.yukon.instrumentation.BootstrapHolder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import io.github.lukedevops.yukon.registry.EndpointRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.description.field.FieldList
import net.bytebuddy.description.method.MethodList
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.pool.TypePool
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
    fun `a route added quietly and declared through publishRoutes is discovered by REGISTRATION with its handler class attached`() {
        val registry = EndpointRegistry()
        val router = install(registry, listOf(FakeRouterModule()), "com.example.framework.FakeRouter")
        val addQuietly = router.javaClass.getMethod("addQuietly", String::class.java, String::class.java, Runnable::class.java)
        val publishRoutes = router.javaClass.getMethod("publishRoutes")
        val handler = Runnable {}

        addQuietly.invoke(router, "GET", "/declared", handler)
        publishRoutes.invoke(router)

        val endpoint = registry.endpoints().single { it.verbatimTemplate == "/declared" }
        assertEquals(EndpointDiscoverySource.REGISTRATION, endpoint.discoverySource)
        assertEquals("fake-router", endpoint.framework)
        assertEquals(handler.javaClass.name, endpoint.handlerClass)
        assertTrue(registry.disabledModules().isEmpty())
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
    fun `a module whose matcher throws leaves the class loadable and untouched`() {
        // A matcher that throws (a supertype walk hitting an unresolvable type, say) fails inside
        // ByteBuddy's own transform call, outside the module's try/catch, so the listener is the
        // only thing that sees it. The class must still define, with no advice woven.
        val throwingModule =
            object : EndpointModule {
                override val name: String = "throwing-matcher"

                override fun typeMatcher(): ElementMatcher<in TypeDescription> =
                    ElementMatcher { type ->
                        if (type.name == "com.example.framework.FakeRouter") throw IllegalStateException("simulated matcher failure")
                        false
                    }

                override fun transform(
                    builder: DynamicType.Builder<*>,
                    typeDescription: TypeDescription,
                    advice: AdviceBinder,
                    classLoader: ClassLoader?,
                ): DynamicType.Builder<*> = builder
            }
        val registry = EndpointRegistry()

        val router = install(registry, listOf(throwingModule), "com.example.framework.FakeRouter")
        val addRoute = router.javaClass.getMethod("addRoute", String::class.java, String::class.java, Runnable::class.java)
        addRoute.invoke(router, "GET", "/checkout", Runnable {})

        assertTrue(registry.endpoints().isEmpty(), "nothing was woven, so nothing registers")
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

    /**
     * Drives the staging seam through a real transform, which the `PendingDeclarations` unit
     * tests cannot: they call begin/commit/discard by hand, so nothing there would notice if
     * [EndpointInstrumentation] stopped calling them.
     */
    @Test
    fun `an endpoint a module declares from its transform is registered once the class is woven`() {
        val registry = EndpointRegistry()
        install(registry, listOf(DeclaringModule()), "com.example.framework.FakeRouter")

        // This one would also pass with no staging at all. It is here to catch commit being
        // dropped from the listener, which would leave the endpoint staged and never declared.
        assertEquals("/declared-in-transform", registry.endpoints().single().verbatimTemplate)
        assertEquals(0, installedEndpointInstrumentation!!.pendingDeclarationCount(), "nothing is left staged on this thread")
    }

    @Test
    fun `an endpoint declared by a transform whose rewrite fails is never registered`() {
        val registry = EndpointRegistry()
        val router = install(registry, listOf(DeclaringModule(failRewrite = true)), "com.example.framework.FakeRouter")

        assertTrue(registry.endpoints().isEmpty(), "the class never got its advice, so its routes are not declared")
        assertEquals(0, installedEndpointInstrumentation!!.pendingDeclarationCount())
        val routes = router.javaClass.getMethod("routes").invoke(router)
        assertTrue(routes is List<*> && routes.isEmpty(), "the class still loads and runs, from its original bytes")
    }

    @Test
    fun `a module that throws after declaring leaves none of its own endpoints behind`() {
        val registry = EndpointRegistry()
        install(registry, listOf(DeclaringModule(throwAfterDeclaring = true)), "com.example.framework.FakeRouter")

        // The lambda catches the throwable and hands the builder back, so the transform succeeds
        // and the listener commits. Without the rollback, the half-read route list lands anyway.
        assertTrue(registry.endpoints().isEmpty(), "a half-read route list is worse than none")
    }

    @Test
    fun `two modules matching one class both keep what they declared`() {
        val registry = EndpointRegistry()
        install(
            registry,
            listOf(
                DeclaringModule(template = "/first"),
                DeclaringModule(template = "/second"),
            ),
            "com.example.framework.FakeRouter",
        )

        assertEquals(
            setOf("/first", "/second"),
            registry.endpoints().mapTo(mutableSetOf()) { it.verbatimTemplate },
            "the second module's begin must not discard what the first staged",
        )
    }
}

/**
 * Declares one endpoint from inside its transform callback, the shape of a module that reads its
 * routes off a class's own annotations. [failRewrite] makes ByteBuddy's `make()` throw after the
 * callback returns; [throwAfterDeclaring] makes the callback itself throw once it has declared.
 */
private class DeclaringModule(
    private val failRewrite: Boolean = false,
    private val throwAfterDeclaring: Boolean = false,
    // Unique per instance. A module this test disables through `moduleFailed` stays disabled for
    // the life of the JVM, and the test JVM is shared, so a fixed name would silence the module
    // for every later test that used it and make their assertions pass for the wrong reason.
    private val moduleName: String = "declaring-${System.nanoTime()}",
    private val template: String = "/declared-in-transform",
) : EndpointModule {
    override val name: String = moduleName

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = named("com.example.framework.FakeRouter")

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> {
        io.github.lukedevops.yukon.bootstrap.YukonEndpoints.register(
            moduleName,
            "$moduleName-key",
            "GET",
            template,
            null,
            typeDescription.name,
            null,
            null,
        )
        if (throwAfterDeclaring) throw IllegalStateException("module gave up after declaring")
        return if (failRewrite) builder.visit(ThrowingAsmVisitorWrapper()) else builder
    }

    override fun declare(frameworkObject: Any) = Unit
}

/** Fails inside ByteBuddy's `make()`, after the transform callback has already returned. */
private class ThrowingAsmVisitorWrapper : AsmVisitorWrapper {
    override fun mergeWriter(flags: Int): Int = flags

    override fun mergeReader(flags: Int): Int = flags

    override fun wrap(
        instrumentedType: TypeDescription,
        classVisitor: ClassVisitor,
        implementationContext: Implementation.Context,
        typePool: TypePool,
        fields: FieldList<FieldDescription.InDefinedShape>,
        methods: MethodList<*>,
        writerFlags: Int,
        readerFlags: Int,
    ): ClassVisitor = throw IllegalStateException("rewrite refused these bytes")
}
