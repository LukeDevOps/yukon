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
import kotlin.test.assertTrue

/**
 * Proves [SpringWebMvcModule] end to end against a real `DispatcherServlet`, driven through
 * Spring's `MockMvc`, not a fixture. [EndpointInstrumentation.install] must run before Spring's
 * own `AbstractHandlerMethodMapping`/`RequestMappingInfoHandlerMapping` machinery loads for the
 * first time in this JVM: an `AgentBuilder` transformer only weaves advice into a class as it
 * loads, not into one already loaded. Every reference to Spring MVC's own machinery and `MockMvc`
 * is therefore kept inside this one test method rather than a field or a companion object, so
 * nothing else in this test class can trigger that load first.
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
}
