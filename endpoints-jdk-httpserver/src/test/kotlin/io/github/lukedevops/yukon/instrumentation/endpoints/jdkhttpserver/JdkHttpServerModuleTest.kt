package io.github.lukedevops.yukon.instrumentation.endpoints.jdkhttpserver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.github.lukedevops.yukon.export.EndpointDiscoverySource
import io.github.lukedevops.yukon.instrumentation.BootstrapHolder
import io.github.lukedevops.yukon.instrumentation.endpoints.EndpointInstrumentation
import io.github.lukedevops.yukon.registry.EndpointRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val HANDLE_DESCRIPTOR = "(Lcom/sun/net/httpserver/HttpExchange;)V"

/** Declares `handle` itself; a subclass with no override of its own must join here, not to itself. */
private abstract class AbstractRespondingHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        exchange.sendResponseHeaders(200, -1)
        exchange.close()
    }
}

/** Inherits `handle` from [AbstractRespondingHandler] without overriding it. */
private class InheritingHandler : AbstractRespondingHandler()

/** Overrides `handle` on itself, so its own class is where the join must land. */
private class OverridingHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        exchange.sendResponseHeaders(200, -1)
        exchange.close()
    }
}

/**
 * Proves [JdkHttpServerModule] end to end against the real `com.sun.net.httpserver.HttpServer`,
 * not a fixture. [EndpointInstrumentation.install] must run before the JDK's own `ServerImpl`,
 * `HttpContextImpl`, and `ContextList` classes load for the first time in this JVM: a
 * [net.bytebuddy.agent.builder.AgentBuilder] transformer only weaves advice into a class as it
 * loads, not into one already loaded. Every reference to `HttpServer` and its context type is
 * therefore kept inside this one test method rather than a field or a companion object, so
 * nothing else in this test class can trigger that load first. Another test class in this module
 * would load those classes too, so the module's `forkEvery = 1` runs each class in its own JVM.
 */
class JdkHttpServerModuleTest {
    @Test
    fun `registration and dispatch through the real JDK HttpServer are tracked end to end`() {
        val instrumentation = ByteBuddyAgent.install()
        val registry = EndpointRegistry()
        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(JdkHttpServerModule()))
        val transformer = endpointInstrumentation.install(instrumentation)

        val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        try {
            val lambdaHandler = HttpHandler { exchange -> respond(exchange) }
            assertTrue(
                lambdaHandler.javaClass.isHidden,
                "a Kotlin SAM lambda for a Java functional interface must compile to a hidden class, confirmed via Class.isHidden()",
            )

            server.createContext("/checkout", lambdaHandler)
            server.createContext("/promo", HttpHandler { exchange -> respond(exchange) })
            server.createContext("/overriding", OverridingHandler())
            server.createContext("/inherited", InheritingHandler())
            val lateContext = server.createContext("/late")
            lateContext.setHandler(InheritingHandler())
            val lateHiddenContext = server.createContext("/late-hidden")
            lateHiddenContext.setHandler(HttpHandler { exchange -> respond(exchange) })
            server.start()

            val port = server.address.port
            val client = HttpClient.newHttpClient()
            get(client, port, "/checkout")
            get(client, port, "/checkout")
            get(client, port, "/checkout/extra")
            get(client, port, "/overriding")
            get(client, port, "/inherited")
            get(client, port, "/late")
            get(client, port, "/late-hidden")
            get(client, port, "/nothing")

            val endpoints = registry.endpoints()
            val byIdentity = endpoints.associateBy { "${it.verb} ${it.routeTemplate}" }
            assertEquals(
                setOf("* /checkout", "* /promo", "* /overriding", "* /inherited", "* /late", "* /late-hidden"),
                byIdentity.keys,
            )
            for (endpoint in endpoints) {
                assertEquals("jdk-httpserver", endpoint.framework)
                assertEquals(EndpointDiscoverySource.REGISTRATION, endpoint.discoverySource)
            }

            // A hidden class (a Kotlin SAM lambda here) joins to the lambda body the lambda factory named for it,
            // whether attached at creation or later through setHandler. Each lambda calls the member `respond`,
            // so kotlinc passes `this` as the body's first parameter. HiddenHandlerNamingTest covers the other shapes.
            val lambdaBodyDescriptor = "(L${javaClass.name.replace('.', '/')};Lcom/sun/net/httpserver/HttpExchange;)V"
            val checkout = byIdentity.getValue("* /checkout")
            assertJoinsDeclaredMethod(checkout, javaClass, "registration_and_dispatch", lambdaBodyDescriptor)
            val promo = byIdentity.getValue("* /promo")
            assertJoinsDeclaredMethod(promo, javaClass, "registration_and_dispatch", lambdaBodyDescriptor)
            val lateHidden = byIdentity.getValue("* /late-hidden")
            assertJoinsDeclaredMethod(lateHidden, javaClass, "registration_and_dispatch", lambdaBodyDescriptor)
            assertTrue(
                setOf(checkout.handlerMethod, promo.handlerMethod, lateHidden.handlerMethod).size == 3,
                "three lambdas, three bodies",
            )

            // A non-hidden handler class that overrides `handle` on itself reports its own class.
            val overriding = byIdentity.getValue("* /overriding")
            assertEquals(OverridingHandler::class.java.name, overriding.handlerClass)
            assertEquals("handle", overriding.handlerMethod)
            assertEquals(HANDLE_DESCRIPTOR, overriding.handlerDescriptor)

            // A subclass that inherits `handle` from an abstract base reports the base class, not the subclass:
            // that is where the probe on `handle` actually lives.
            val inherited = byIdentity.getValue("* /inherited")
            assertEquals(AbstractRespondingHandler::class.java.name, inherited.handlerClass)
            assertEquals("handle", inherited.handlerMethod)
            assertEquals(HANDLE_DESCRIPTOR, inherited.handlerDescriptor)

            // A handler attached later through setHandler (the one-argument createContext overload) is joined
            // the same way as one attached at creation.
            val late = byIdentity.getValue("* /late")
            assertEquals(AbstractRespondingHandler::class.java.name, late.handlerClass)
            assertEquals("handle", late.handlerMethod)
            assertEquals(HANDLE_DESCRIPTOR, late.handlerDescriptor)

            val deltasById = registry.computeDeltas(maxPerBatch = 10).flatMap { it.deltas }.associateBy { it.endpointId }
            assertEquals(3L, deltasById.getValue(checkout.endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(overriding.endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(inherited.endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(late.endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(lateHidden.endpointId).hitsTotal)
            assertTrue(promo.endpointId !in deltasById, "an endpoint never dispatched to must report no delta")

            assertTrue(registry.disabledModules().isEmpty())

            val bootModule = ModuleLayer.boot().findModule("jdk.httpserver").get()
            val seamModule = Class.forName(BootstrapHolder.ENDPOINTS_CLASS_NAME, false, null).module
            assertTrue(bootModule.canRead(seamModule))
        } finally {
            server.stop(0)
            endpointInstrumentation.uninstall(instrumentation, transformer)
        }
    }

    private fun get(
        client: HttpClient,
        port: Int,
        path: String,
    ) {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build()
        client.send(request, HttpResponse.BodyHandlers.discarding())
    }

    private fun respond(exchange: HttpExchange) {
        exchange.sendResponseHeaders(200, -1)
        exchange.close()
    }
}
