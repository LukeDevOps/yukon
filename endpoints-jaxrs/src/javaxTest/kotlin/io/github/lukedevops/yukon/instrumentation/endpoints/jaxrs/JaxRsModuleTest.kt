package io.github.lukedevops.yukon.instrumentation.endpoints.jaxrs

import com.example.jaxrs.fixture.ApiOrdersResource
import com.example.jaxrs.fixture.OrdersResource
import com.example.jaxrs.fixture.ReportsResource
import io.github.lukedevops.yukon.export.EndpointDiscoverySource
import io.github.lukedevops.yukon.export.ResourceAttributes
import org.glassfish.jersey.jdkhttp.JdkHttpServerFactory
import org.glassfish.jersey.server.ResourceConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves [JaxRsModule] end to end against a real Jersey server on the JDK's own `HttpServer`, not
 * a fixture-level unit test, and proves the method tier and the endpoint tier coexist on one
 * resource method: [OrdersResource] sits under [JaxRsTestAgent]'s fixture package, so both
 * `YukonInstrumentation` and `EndpointInstrumentation` instrument it.
 *
 * [JaxRsTestAgent] installs both transformers during its own `premain`, run by this module's
 * Gradle configuration as a real `-javaagent` on the test JVM's own command line; see that
 * object's Javadoc for why a self-attach inside this test method would be too late.
 *
 * This is `:endpoints-jaxrs`'s `javax.ws.rs` copy of the default `test` suite's `jakarta.ws.rs`
 * test of the same name. Only the fixtures' own imports, [OrdersResource]'s `PURGE` annotation
 * included, differ by namespace; every line in this file, including the assertions, is identical.
 * Sharing this file across both suites was considered and set aside: the fixtures' own imports
 * differ by namespace, so a shared test source would still need two copies of every fixture and a
 * parameter to select between them, which is more indirection than duplicating one file plainly.
 */
class JaxRsModuleTest {
    @Test
    fun `registration and dispatch through a real Jersey server are tracked end to end`() {
        val endpointRegistry = JaxRsTestAgent.endpointRegistry
        val probeRegistry = JaxRsTestAgent.probeRegistry

        val resourceConfig = ResourceConfig(OrdersResource::class.java, ApiOrdersResource::class.java, ReportsResource::class.java)
        val server = JdkHttpServerFactory.createHttpServer(URI.create("http://localhost:0/"), resourceConfig)

        try {
            val port = server.address.port
            val client = HttpClient.newHttpClient()
            get(client, port, "/orders/42")
            get(client, port, "/orders/42")
            post(client, port, "/orders")
            get(client, port, "/orders/sub/leaf")
            purge(client, port, "/orders/42/purge")
            get(client, port, "/api/orders/7")
            post(client, port, "/api/orders")
            get(client, port, "/reports/summary")
            val auditStatus = status(client, port, "/api/orders/7/audit")

            assertEquals(404, auditStatus, "audit inherits nothing under the all-or-nothing rule, so Jersey has no route for it")

            val endpoints = endpointRegistry.endpoints()
            val byIdentity = endpoints.associateBy { "${it.verb} ${it.routeTemplate}" }
            assertEquals(
                setOf(
                    "GET /orders/{id}",
                    "POST /orders",
                    "GET /orders/{id}/invoice",
                    "DELETE /orders/{id}",
                    "* /orders/sub",
                    "GET /sub/leaf",
                    "PURGE /orders/{id}/purge",
                    "GET /api/orders/{id}",
                    "POST /api/orders",
                    "GET /reports/summary",
                ),
                byIdentity.keys,
            )
            assertEquals(ApiOrdersResource::class.java.name, byIdentity.getValue("GET /api/orders/{id}").handlerClass)
            assertEquals(ApiOrdersResource::class.java.name, byIdentity.getValue("POST /api/orders").handlerClass)
            assertEquals(ReportsResource::class.java.name, byIdentity.getValue("GET /reports/summary").handlerClass)

            for (endpoint in endpoints) {
                assertEquals("jaxrs", endpoint.framework)
                assertEquals(EndpointDiscoverySource.REGISTRATION, endpoint.discoverySource)
                assertNotNull(endpoint.handlerClass, "handler class missing for ${endpoint.verbatimTemplate}")
                assertNotNull(endpoint.handlerMethod, "handler method missing for ${endpoint.verbatimTemplate}")
                assertNotNull(endpoint.handlerDescriptor, "handler descriptor missing for ${endpoint.verbatimTemplate}")
            }

            val deltasById = endpointRegistry.computeDeltas(maxPerBatch = 10).flatMap { it.deltas }.associateBy { it.endpointId }
            assertEquals(2L, deltasById.getValue(byIdentity.getValue("GET /orders/{id}").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(byIdentity.getValue("POST /orders").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(byIdentity.getValue("* /orders/sub").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(byIdentity.getValue("GET /sub/leaf").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(byIdentity.getValue("PURGE /orders/{id}/purge").endpointId).hitsTotal)
            assertTrue(byIdentity.getValue("GET /orders/{id}/invoice").endpointId !in deltasById)
            assertTrue(byIdentity.getValue("DELETE /orders/{id}").endpointId !in deltasById)
            assertEquals(1L, deltasById.getValue(byIdentity.getValue("GET /api/orders/{id}").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(byIdentity.getValue("POST /api/orders").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(byIdentity.getValue("GET /reports/summary").endpointId).hitsTotal)

            assertTrue(endpointRegistry.disabledModules().isEmpty())

            val manifest = probeRegistry.manifest(ResourceAttributes("jaxrs-test", null, "instance-1", null, "run-1"))
            val getOrderProbe = manifest.probes.single { it.className.endsWith("OrdersResource") && it.methodName == "getOrder" }
            val methodDeltas =
                probeRegistry.computeDeltaBatch(ResourceAttributes("jaxrs-test", null, "instance-1", null, "run-1")).batch.deltas
            assertEquals(
                2L,
                methodDeltas.single { it.classId == getOrderProbe.classId && it.probeIndex == getOrderProbe.probeIndex }.hitsTotal,
            )
        } finally {
            server.stop(0)
            JaxRsTestAgent.uninstall()
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

    private fun post(
        client: HttpClient,
        port: Int,
        path: String,
    ) {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).POST(BodyPublishers.noBody()).build()
        client.send(request, HttpResponse.BodyHandlers.discarding())
    }

    private fun purge(
        client: HttpClient,
        port: Int,
        path: String,
    ) {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).method("PURGE", BodyPublishers.noBody()).build()
        client.send(request, HttpResponse.BodyHandlers.discarding())
    }

    /** The HTTP status Jersey answers a `GET` to [path] with, used to prove a declared-or-not endpoint matches what actually gets served. */
    private fun status(
        client: HttpClient,
        port: Int,
        path: String,
    ): Int {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build()
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }
}
