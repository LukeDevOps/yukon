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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves [JdkHttpServerModule] end to end against the real `com.sun.net.httpserver.HttpServer`,
 * not a fixture. [EndpointInstrumentation.install] must run before the JDK's own `ServerImpl`,
 * `HttpContextImpl`, and `ContextList` classes load for the first time in this JVM: a
 * [net.bytebuddy.agent.builder.AgentBuilder] transformer only weaves advice into a class as it
 * loads, not into one already loaded. Every reference to `HttpServer` and its context type is
 * therefore kept inside this one test method rather than a field or a companion object, so
 * nothing else in this test class can trigger that load first.
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
            server.createContext("/checkout", HttpHandler { exchange -> respond(exchange) })
            server.createContext("/promo", HttpHandler { exchange -> respond(exchange) })
            val lateContext = server.createContext("/late")
            lateContext.setHandler(HttpHandler { exchange -> respond(exchange) })
            server.start()

            val port = server.address.port
            val client = HttpClient.newHttpClient()
            get(client, port, "/checkout")
            get(client, port, "/checkout")
            get(client, port, "/checkout/extra")
            get(client, port, "/late")
            get(client, port, "/nothing")

            val endpoints = registry.endpoints()
            val byIdentity = endpoints.associateBy { "${it.verb} ${it.routeTemplate}" }
            assertEquals(setOf("* /checkout", "* /promo", "* /late"), byIdentity.keys)
            for (endpoint in endpoints) {
                assertEquals("jdk-httpserver", endpoint.framework)
                assertEquals(EndpointDiscoverySource.REGISTRATION, endpoint.discoverySource)
                assertNotNull(endpoint.handlerClass, "handler class missing for ${endpoint.verbatimTemplate}")
            }

            val deltasById = registry.computeDeltas(maxPerBatch = 10).flatMap { it.deltas }.associateBy { it.endpointId }
            val checkoutId = byIdentity.getValue("* /checkout").endpointId
            val lateId = byIdentity.getValue("* /late").endpointId
            val promoId = byIdentity.getValue("* /promo").endpointId
            assertEquals(3L, deltasById.getValue(checkoutId).hitsTotal)
            assertEquals(1L, deltasById.getValue(lateId).hitsTotal)
            assertTrue(promoId !in deltasById)

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
