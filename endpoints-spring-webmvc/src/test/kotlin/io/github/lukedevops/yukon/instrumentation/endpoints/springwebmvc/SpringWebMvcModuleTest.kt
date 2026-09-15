package io.github.lukedevops.yukon.instrumentation.endpoints.springwebmvc

import io.github.lukedevops.yukon.export.EndpointDiscoverySource
import io.github.lukedevops.yukon.instrumentation.endpoints.EndpointInstrumentation
import io.github.lukedevops.yukon.registry.EndpointRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.lang.invoke.MethodType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves [SpringWebMvcModule] end to end against a real `DispatcherServlet`, driven through
 * Spring's `MockMvc`, not a fixture. [EndpointInstrumentation.install] must run before Spring's
 * own handler-mapping machinery loads for the first time in this JVM: an `AgentBuilder`
 * transformer only weaves advice into a class as it loads, not into one already loaded. Every
 * reference to Spring MVC's own machinery and `MockMvc` is therefore kept inside the one test
 * method that needs it, rather than a field or a companion object, so nothing else in this test
 * class can trigger that load first.
 *
 * That is enough for the annotation-mapped side below, whose handler-mapping types are never
 * named as a compile-time type anywhere in this module's test sources; Spring only resolves them
 * lazily, from inside its own code, once `MockMvc` actually dispatches a request. The URL-mapped
 * test needs a second precaution beyond this one; see [buildUrlMappedWebApplicationContext]'s
 * KDoc.
 *
 * Runs unmodified against Spring Framework 5.3, 6.x and 7.x: this module's own advice binds no
 * servlet type, so the same test source compiles and passes against all three, each its own
 * Gradle test suite (`test` against 6.x, `spring7Test` against 7.x) with its own classpath and its
 * own forked test JVM.
 */
class SpringWebMvcModuleTest {
    @Test
    fun `registration and dispatch through a real DispatcherServlet are tracked end to end`() {
        val instrumentation = ByteBuddyAgent.install()
        val registry = EndpointRegistry()
        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(SpringWebMvcModule()))
        val transformer = endpointInstrumentation.install(instrumentation)

        try {
            val controller = TestController()
            val mockMvc = MockMvcBuilders.standaloneSetup(controller).build()

            mockMvc.perform(get("/checkout/42"))
            mockMvc.perform(get("/checkout/42"))
            mockMvc.perform(get("/a"))
            mockMvc.perform(get("/any"))

            val endpoints = registry.endpoints()
            val byIdentity = endpoints.associateBy { "${it.verb} ${it.routeTemplate}" }
            assertEquals(
                setOf("GET /checkout/{id}", "POST /promo", "GET /a", "POST /a", "GET /b", "POST /b", "* /any"),
                byIdentity.keys,
            )

            val controllerClassName = controller.javaClass.name
            for (endpoint in endpoints) {
                assertEquals("spring-webmvc", endpoint.framework)
                assertEquals(EndpointDiscoverySource.REGISTRATION, endpoint.discoverySource)
                assertEquals(controllerClassName, endpoint.handlerClass)
                assertNotNull(endpoint.handlerMethod, "handler method missing for ${endpoint.verbatimTemplate}")
                assertNotNull(endpoint.handlerDescriptor, "handler descriptor missing for ${endpoint.verbatimTemplate}")
            }

            val checkoutDescriptor = MethodType.methodType(String::class.java, String::class.java).toMethodDescriptorString()
            assertEquals(checkoutDescriptor, byIdentity.getValue("GET /checkout/{id}").handlerDescriptor)

            val deltasById = registry.computeDeltas(maxPerBatch = 10).flatMap { it.deltas }.associateBy { it.endpointId }
            assertEquals(2L, deltasById.getValue(byIdentity.getValue("GET /checkout/{id}").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(byIdentity.getValue("GET /a").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(byIdentity.getValue("* /any").endpointId).hitsTotal)
            assertTrue(byIdentity.getValue("POST /promo").endpointId !in deltasById)
            assertTrue(byIdentity.getValue("POST /a").endpointId !in deltasById)
            assertTrue(byIdentity.getValue("GET /b").endpointId !in deltasById)
            assertTrue(byIdentity.getValue("POST /b").endpointId !in deltasById)

            assertTrue(registry.disabledModules().isEmpty())
        } finally {
            endpointInstrumentation.uninstall(instrumentation, transformer)
        }
    }

    /**
     * A `SimpleUrlHandlerMapping` bean is Spring MVC's other handler-mapping style: a URL matched
     * directly to a handler object rather than to an annotated method. This proves that style is
     * declared and counted the same way, through [SpringWebMvcModule]'s
     * `AbstractUrlHandlerMapping` advice, alongside [TestController]'s annotation-mapped endpoints
     * in the same context, so the two mapping styles are proven to coexist rather than only in
     * isolation from each other.
     *
     * The context itself is built by [buildUrlMappedWebApplicationContext] rather than inline
     * here; see that function's KDoc for why `SimpleUrlHandlerMapping` must never be referenced
     * directly from this class's own bytecode.
     */
    @Test
    fun `URL-mapped handlers are declared and counted alongside annotation-mapped ones`() {
        val instrumentation = ByteBuddyAgent.install()
        val registry = EndpointRegistry()
        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(SpringWebMvcModule()))
        val transformer = endpointInstrumentation.install(instrumentation)

        try {
            val staticHandler = urlMappedTestHandler("static content")
            val legacyHandler = urlMappedTestHandler("legacy content")

            val context = buildUrlMappedWebApplicationContext(staticHandler, legacyHandler)
            val mockMvc = MockMvcBuilders.webAppContextSetup(context).build()

            mockMvc.perform(get("/checkout/42"))
            mockMvc.perform(get("/static/app.js"))
            mockMvc.perform(get("/static/app.js"))
            mockMvc.perform(get("/legacy/old"))

            val endpoints = registry.endpoints()
            val byIdentity = endpoints.associateBy { "${it.verb} ${it.routeTemplate}" }
            assertEquals(
                setOf(
                    "GET /checkout/{id}",
                    "POST /promo",
                    "GET /a",
                    "POST /a",
                    "GET /b",
                    "POST /b",
                    "* /any",
                    "* /static/*",
                    "* /legacy/old",
                ),
                byIdentity.keys,
            )

            val staticEndpoint = byIdentity.getValue("* /static/*")
            assertEquals(EndpointDiscoverySource.REGISTRATION, staticEndpoint.discoverySource)
            assertEquals(staticHandler.javaClass.name, staticEndpoint.handlerClass)
            assertNull(staticEndpoint.handlerMethod)

            val legacyEndpoint = byIdentity.getValue("* /legacy/old")
            assertEquals(EndpointDiscoverySource.REGISTRATION, legacyEndpoint.discoverySource)
            assertEquals(legacyHandler.javaClass.name, legacyEndpoint.handlerClass)
            assertNull(legacyEndpoint.handlerMethod)

            val checkoutEndpoint = byIdentity.getValue("GET /checkout/{id}")
            assertEquals(EndpointDiscoverySource.REGISTRATION, checkoutEndpoint.discoverySource)
            assertEquals(TestController::class.java.name, checkoutEndpoint.handlerClass)

            val deltasById = registry.computeDeltas(maxPerBatch = 10).flatMap { it.deltas }.associateBy { it.endpointId }
            assertEquals(2L, deltasById.getValue(staticEndpoint.endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(legacyEndpoint.endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(checkoutEndpoint.endpointId).hitsTotal)
            assertTrue(byIdentity.getValue("POST /promo").endpointId !in deltasById)

            assertTrue(registry.disabledModules().isEmpty())
        } finally {
            endpointInstrumentation.uninstall(instrumentation, transformer)
        }
    }
}
