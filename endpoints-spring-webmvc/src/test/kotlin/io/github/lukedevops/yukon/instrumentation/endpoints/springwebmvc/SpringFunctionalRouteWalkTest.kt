package io.github.lukedevops.yukon.instrumentation.endpoints.springwebmvc

import io.github.lukedevops.yukon.instrumentation.endpoints.EndpointInstrumentation
import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.registry.EndpointRegistry
import net.bytebuddy.ByteBuddy
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.description.type.TypeDescription
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.RequestPredicate
import org.springframework.web.servlet.function.RequestPredicates
import org.springframework.web.servlet.function.RequestPredicates.GET
import org.springframework.web.servlet.function.RequestPredicates.param
import org.springframework.web.servlet.function.RequestPredicates.path
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.RouterFunctions.route
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Drives [SpringWebMvcModule.declare] against real `RouterFunction` objects, one predicate shape
 * per test, to pin what each arm of the predicate walk resolves a route's identity to.
 *
 * [SpringWebMvcModuleTest] proves the same walk end to end through a real `DispatcherServlet`, but
 * it can only reach the predicate shapes an application would normally build. The combinations
 * here (`and` of two paths, a negated predicate, a predicate the walk cannot interpret, one
 * reporting no verbs) are all legal for any `RequestPredicate` implementation to produce, and
 * several of them no `RequestPredicates` factory can: `RequestPredicates.methods()` asserts its
 * argument is non-empty, so only a predicate written by hand can hand the visitor an empty verb
 * set. Checked against the 5.3.39 sources, whose `RequestPredicate.accept` default body is
 * `visitor.unknown(this)` and whose `HttpMethodPredicate` carries that assertion.
 *
 * [SpringWebMvcModule.declare] is called directly rather than through the woven
 * `initRouterFunctions` advice on purpose. Weaving needs `RouterFunctionMapping` to load after
 * `EndpointInstrumentation.install`, which can happen only once per JVM, and
 * [SpringWebMvcModuleTest] already spends that one chance; nothing here loads a handler-mapping
 * class at all. The registry still receives every route through the real seam, since
 * `install` is what puts the resolver behind `YukonEndpoints.register`.
 */
class SpringFunctionalRouteWalkTest {
    private val handler = HandlerFunction<ServerResponse> { ServerResponse.ok().build() }

    /** A predicate whose `accept` is Spring's own default body, which reports it as unrecognisable. */
    private class OpaquePredicate : RequestPredicate {
        override fun test(request: ServerRequest): Boolean = true
    }

    /**
     * A predicate that calls `Object`'s own methods on the visitor before describing itself.
     * A [java.lang.reflect.Proxy] receives those calls like any other, and the handler has to
     * answer them: returning null for `hashCode` would raise a `NullPointerException` inside the
     * framework's own `accept`.
     */
    private class ChattyPredicate : RequestPredicate {
        override fun test(request: ServerRequest): Boolean = true

        override fun accept(visitor: RequestPredicates.Visitor) {
            visitor.toString()
            visitor.hashCode()
            @Suppress("EqualsBetweenInconvertibleTypes")
            visitor.equals(visitor)
            visitor.path("/probe")
        }
    }

    /** A router function that calls `Object`'s own methods on the router visitor, then one route. */
    private class ChattyRouterFunction(
        private val handler: HandlerFunction<ServerResponse>,
    ) : RouterFunction<ServerResponse> {
        override fun route(request: ServerRequest): Optional<HandlerFunction<ServerResponse>> = Optional.empty()

        override fun accept(visitor: RouterFunctions.Visitor) {
            visitor.toString()
            visitor.hashCode()
            @Suppress("EqualsBetweenInconvertibleTypes")
            visitor.equals(visitor)
            visitor.route(GET("/probe"), handler)
        }
    }

    /** A router function with Spring's own default `accept`, which reports itself as unrecognisable. */
    private class OpaqueRouterFunction : RouterFunction<ServerResponse> {
        override fun route(request: ServerRequest): Optional<HandlerFunction<ServerResponse>> = Optional.empty()
    }

    /** A predicate that describes itself to the visitor not at all, leaving the operand stack empty. */
    private class SilentPredicate : RequestPredicate {
        override fun test(request: ServerRequest): Boolean = true

        override fun accept(visitor: RequestPredicates.Visitor) = Unit
    }

    /** A predicate that constrains the verb to nothing at all, which no `RequestPredicates` factory builds. */
    private class NoVerbPredicate : RequestPredicate {
        override fun test(request: ServerRequest): Boolean = true

        override fun accept(visitor: RequestPredicates.Visitor) {
            visitor.method(emptySet())
        }
    }

    @Test
    fun `an and of two path predicates declares the two paths joined`() {
        val declared = declaredIdentities(route(path("/outer").and(path("/inner")), handler))

        assertEquals(setOf("* /outer/inner"), declared)
    }

    @Test
    fun `an and of a path and a predicate that narrows nothing declares the path alone`() {
        val declared = declaredIdentities(route(path("/orders").and(param("mine", "1")), handler))

        assertEquals(setOf("* /orders"), declared)
    }

    @Test
    fun `an and of two verb predicates keeps the first verb`() {
        val bothVerbs = RequestPredicates.method(HttpMethod.GET).and(RequestPredicates.method(HttpMethod.POST))

        val declared = declaredIdentities(route(bothVerbs.and(path("/report")), handler))

        // No request can be both verbs at once, so this route is unreachable whichever verb the
        // walk keeps. Pinned to say the walk resolves it to one identity rather than to none or
        // to both, which is what a collector would have to make sense of.
        assertEquals(setOf("GET /report"), declared)
    }

    @Test
    fun `a negated predicate narrows nothing, so a route with no other path is not declared`() {
        val declared = declaredIdentities(route(GET("/legacy").negate(), handler))

        assertEquals(emptySet(), declared)
    }

    @Test
    fun `a negated predicate beside a path leaves the path as the identity`() {
        val declared = declaredIdentities(route(path("/orders").and(param("mine", "1").negate()), handler))

        assertEquals(setOf("* /orders"), declared)
    }

    @Test
    fun `a predicate the walk cannot interpret leaves the route undeclared, to be found at dispatch`() {
        val declared = declaredIdentities(route(OpaquePredicate(), handler))

        assertEquals(emptySet(), declared)
    }

    @Test
    fun `an unrecognisable predicate poisons the route it is combined with, path and all`() {
        val declared = declaredIdentities(route(path("/orders").and(OpaquePredicate()), handler))

        assertEquals(emptySet(), declared, "an identity resolved from a tree with an unknown part is not trustworthy")
    }

    @Test
    fun `a predicate reporting no verbs narrows nothing, leaving the route's own path and any verb`() {
        val declared = declaredIdentities(route(NoVerbPredicate().and(path("/health")), handler))

        assertEquals(setOf("* /health"), declared)
    }

    @Test
    fun `a nest whose predicate carries no path contributes no prefix segment`() {
        val nested = route(GET("/items"), handler)

        val declared = declaredIdentities(RouterFunctions.nest(param("tenant", "acme"), nested))

        assertEquals(setOf("GET /items"), declared)
    }

    @Test
    fun `an or inside a nest declares one route per prefix`() {
        val nested = route(GET("/items"), handler)

        val declared = declaredIdentities(RouterFunctions.nest(path("/api").or(path("/v2")), nested))

        assertEquals(setOf("GET /api/items", "GET /v2/items"), declared)
    }

    @Test
    fun `a predicate that describes itself not at all narrows nothing`() {
        val declared = declaredIdentities(route(SilentPredicate().and(path("/quiet")), handler))

        assertEquals(setOf("* /quiet"), declared)
    }

    @Test
    fun `negating a predicate that describes itself not at all narrows nothing either`() {
        val declared = declaredIdentities(route(SilentPredicate().negate().and(path("/quiet")), handler))

        assertEquals(setOf("* /quiet"), declared)
    }

    @Test
    fun `an or of a path and a predicate with none declares only the alternative that has a path`() {
        val declared = declaredIdentities(route(path("/orders").or(param("mine", "1")), handler))

        assertEquals(setOf("* /orders"), declared)
    }

    @Test
    fun `a header predicate narrows nothing, leaving the path beside it as the identity`() {
        val jsonFeed = RequestPredicates.accept(MediaType.APPLICATION_JSON).and(path("/feed"))

        val declared = declaredIdentities(route(jsonFeed, handler))

        assertEquals(setOf("* /feed"), declared)
    }

    @Test
    fun `a router function Spring cannot describe declares nothing and does not fail the walk`() {
        val declared = declaredIdentities(OpaqueRouterFunction())

        assertEquals(emptySet(), declared)
    }

    @Test
    fun `a resources route declares nothing, since its pattern belongs to the nest around it`() {
        val declared = declaredIdentities(RouterFunctions.resources("/static/**", ClassPathResource("static/")))

        assertEquals(emptySet(), declared)
    }

    @Test
    fun `a route carrying attributes is declared, and its attributes are ignored`() {
        val withAttributes =
            RouterFunctions
                .route()
                .GET("/audited", handler)
                .withAttribute("audit", true)
                .build()

        val declared = declaredIdentities(withAttributes)

        assertEquals(setOf("GET /audited"), declared)
    }

    @Test
    fun `the predicate visitor answers Object's own methods rather than failing the walk`() {
        val declared = declaredIdentities(route(ChattyPredicate(), handler))

        assertEquals(setOf("* /probe"), declared)
    }

    @Test
    fun `the router visitor answers Object's own methods rather than failing the walk`() {
        val declared = declaredIdentities(ChattyRouterFunction(handler))

        assertEquals(setOf("GET /probe"), declared)
    }

    @Test
    fun `declare ignores an object from the application's own loader that is not a RouterFunction`() {
        // Any object whose loader can see `RouterFunction`, so the walk gets as far as its own
        // type guard. The guard sits after that `Class.forName`, so an object from a loader with
        // no Spring at all (a `String`, on the bootstrap loader) raises `ClassNotFoundException`
        // instead of returning quietly. Unreachable through `InitRouterFunctionsAdvice`, whose
        // only argument is `RouterFunctionMapping`'s own `routerFunction` field.
        val declared = declaredIdentities(ServerResponse.ok().build())

        assertEquals(emptySet(), declared)
    }

    @Test
    fun `transform returns the builder untouched for a type the matcher does not name`() {
        val builder = ByteBuddy().redefine(UnrelatedType::class.java)
        val advice = AdviceBinder(javaClass.classLoader, javaClass.classLoader)

        val returned =
            SpringWebMvcModule().transform(
                builder,
                TypeDescription.ForLoadedType.of(UnrelatedType::class.java),
                advice,
                javaClass.classLoader,
            )

        assertSame(builder, returned, "an unmatched type must be handed back unchanged, not visited")
    }

    /** A type no [SpringWebMvcModule.typeMatcher] name matches, for the unmatched-transform case. */
    private class UnrelatedType

    /**
     * Every `"<verb> <template>"` a fresh [SpringWebMvcModule] declares for [frameworkObject],
     * through a registry of its own so one test's routes are never another's.
     */
    private fun declaredIdentities(frameworkObject: Any): Set<String> {
        val instrumentation = ByteBuddyAgent.install()
        val registry = EndpointRegistry()
        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(SpringWebMvcModule()))
        val transformer = endpointInstrumentation.install(instrumentation)
        try {
            SpringWebMvcModule().declare(frameworkObject)
            val endpoints = registry.endpoints()
            assertTrue(
                endpoints.all { it.framework == "spring-webmvc" },
                "every declared endpoint belongs to this module: $endpoints",
            )
            return endpoints.map { "${it.verb} ${it.routeTemplate}" }.toSet()
        } finally {
            instrumentation.removeTransformer(transformer)
        }
    }
}
