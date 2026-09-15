package io.github.lukedevops.yukon.instrumentation.endpoints.springwebmvc

import io.github.lukedevops.yukon.export.EndpointDiscoverySource
import io.github.lukedevops.yukon.instrumentation.endpoints.EndpointInstrumentation
import io.github.lukedevops.yukon.registry.EndpointRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.RequestPredicates.GET
import org.springframework.web.servlet.function.RequestPredicates.POST
import org.springframework.web.servlet.function.RequestPredicates.method
import org.springframework.web.servlet.function.RequestPredicates.param
import org.springframework.web.servlet.function.RequestPredicates.path
import org.springframework.web.servlet.function.RouterFunctions.route
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.lang.invoke.MethodType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * Proves [SpringWebMvcModule.declare] against a real `RouterFunction`, Spring MVC's
     * functional routing style, alongside the annotation- and URL-mapped styles the two tests
     * above already cover. `RouterFunctionMapping` must not load before
     * [EndpointInstrumentation.install] has run, for the same reason `SimpleUrlHandlerMapping`
     * must not; see [buildFunctionalWebApplicationContext]'s KDoc for why nothing here needs the
     * same reflective precaution [buildUrlMappedWebApplicationContext] does.
     *
     * Every handler here is written in Kotlin, not Java: adding a Java source set to this
     * module's `spring7Test`/`spring53Test` suites, which currently only add
     * `kotlin.srcDir("src/test/kotlin")` and would need their own `java.srcDir` wiring too, was
     * judged more churn than the coverage is worth here. Kotlin's default `indy`-based SAM
     * conversion for a Java functional interface such as `HandlerFunction`, though, makes a
     * trailing-lambda literal (`HandlerFunction { ... }`) compile to a hidden class, the same as
     * a genuine Java lambda, not the named class this project's `CLAUDE.md` describes for a
     * Kotlin function-type lambda; confirmed empirically while writing this test. handlerA and
     * handlerC are therefore written as Kotlin object expressions instead, which always compile
     * to a real named class, so both the named-class join and the hidden-class no-join path are
     * proven here from Kotlin alone, without needing a Java source set.
     */
    @Test
    fun `functional routes are declared through RouterFunction and counted at dispatch`() {
        val instrumentation = ByteBuddyAgent.install()
        val registry = EndpointRegistry()
        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(SpringWebMvcModule()))
        val transformer = endpointInstrumentation.install(instrumentation)

        try {
            // handlerA and handlerC are Kotlin object expressions, not SAM-converted lambdas:
            // Kotlin's default indy-based SAM conversion for a Java functional interface such as
            // HandlerFunction produces a hidden class, the same as a Java lambda, not the named
            // class CLAUDE.md's "Join" design describes for a genuine Kotlin lambda. An object
            // expression always compiles to a real named class, so these two exercise the
            // non-hidden join path; handlerB, handlerD and handlerE stay plain SAM lambdas and
            // exercise the hidden-class, no-join path instead.
            val handlerA =
                object : HandlerFunction<ServerResponse> {
                    override fun handle(request: ServerRequest): ServerResponse = ServerResponse.ok().build()
                }
            val handlerB = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val handlerC =
                object : HandlerFunction<ServerResponse> {
                    override fun handle(request: ServerRequest): ServerResponse = ServerResponse.ok().build()
                }
            val handlerD = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }
            val handlerE = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }

            val routerFunction =
                route(GET("/fn/{id}"), handlerA)
                    .andRoute(POST("/fn"), handlerB)
                    .andNest(
                        path("/api"),
                        route(GET("/items"), handlerC)
                            .andRoute(GET("/items/{id}").or(GET("/things/{id}")), handlerD),
                    ).andRoute(method(HttpMethod.GET).and(param("custom", "1")), handlerE)

            val context = buildFunctionalWebApplicationContext(routerFunction)
            val mockMvc = MockMvcBuilders.webAppContextSetup(context).build()

            val declared = registry.endpoints()
            val declaredByIdentity = declared.associateBy { "${it.verb} ${it.routeTemplate}" }
            assertEquals(
                setOf("GET /fn/{id}", "POST /fn", "GET /api/items", "GET /api/items/{id}", "GET /api/things/{id}"),
                declaredByIdentity.keys,
            )
            for (endpoint in declared) {
                assertEquals(EndpointDiscoverySource.REGISTRATION, endpoint.discoverySource)
                assertEquals("spring-webmvc", endpoint.framework)
            }
            assertFalse(handlerA.javaClass.isHidden, "handlerA is an object expression and must not be a hidden class")
            assertTrue(handlerB.javaClass.isHidden, "handlerB is a SAM-converted lambda and must be a hidden class")
            assertEquals(handlerA.javaClass.name, declaredByIdentity.getValue("GET /fn/{id}").handlerClass)
            assertNull(declaredByIdentity.getValue("POST /fn").handlerClass, "a hidden-class handler must get no join")
            assertEquals(handlerC.javaClass.name, declaredByIdentity.getValue("GET /api/items").handlerClass)
            assertNull(declaredByIdentity.getValue("GET /api/items/{id}").handlerClass, "a hidden-class handler must get no join")
            assertNull(declaredByIdentity.getValue("GET /api/things/{id}").handlerClass, "a hidden-class handler must get no join")

            mockMvc.perform(get("/fn/1"))
            mockMvc.perform(get("/fn/1"))
            mockMvc.perform(post("/fn"))
            mockMvc.perform(get("/api/items"))
            mockMvc.perform(get("/api/things/9"))
            mockMvc.perform(get("/anything").param("custom", "1"))

            val afterDispatch = registry.endpoints().associateBy { "${it.verb} ${it.routeTemplate}" }
            val deltasById = registry.computeDeltas(maxPerBatch = 20).flatMap { it.deltas }.associateBy { it.endpointId }

            assertEquals(2L, deltasById.getValue(afterDispatch.getValue("GET /fn/{id}").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(afterDispatch.getValue("POST /fn").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(afterDispatch.getValue("GET /api/items").endpointId).hitsTotal)
            assertTrue(afterDispatch.getValue("GET /api/items/{id}").endpointId !in deltasById)
            assertEquals(1L, deltasById.getValue(afterDispatch.getValue("GET /api/things/{id}").endpointId).hitsTotal)

            // Observed: the param-only route has no path predicate anywhere in its tree, so
            // nothing ever sets RouterFunctions.MATCHING_PATTERN_ATTRIBUTE on the request, even
            // though the route matches and its handler runs (status 200). SetAttributesAdvice
            // reads that attribute as null and returns without recording anything, per its own
            // "no pattern means no route to attribute" rule. This route is therefore invisible to
            // this module on both sides: never declared (registerRoute already skips a predicate
            // with no path), and never discovered at dispatch either, unlike the annotation-mapped
            // side's unconstrained mapping (HandleMatchAdvice's "* /any" case), which always has a
            // pattern to key off of. registry.endpoints() is unchanged by this dispatch.
            assertEquals(declaredByIdentity.keys, registry.endpoints().associateBy { "${it.verb} ${it.routeTemplate}" }.keys)

            assertTrue(registry.disabledModules().isEmpty())
        } finally {
            endpointInstrumentation.uninstall(instrumentation, transformer)
        }
    }
}
