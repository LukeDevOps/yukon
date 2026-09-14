package io.github.lukedevops.yukon.instrumentation.endpoints.ktor2

import io.github.lukedevops.yukon.export.EndpointDiscoverySource
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves [Ktor2Module] end to end against a real embedded CIO server, not a fixture.
 *
 * [Ktor2TestAgent] installs the endpoint transformer during its own `premain`, run by this
 * module's Gradle configuration as a real `-javaagent` on the test JVM's own command line, rather
 * than through the self-attach `JdkHttpServerModuleTest` and `SpringWebMvcModuleTest` both use.
 * See [Ktor2TestAgent]'s Javadoc for why: Ktor's routing DSL is built from Kotlin `inline`
 * functions, so any code that calls it, anywhere in this module's compiled test output, loads
 * `Route`/`Routing` while Gradle's JUnit Platform integration discovers `@Test` methods, before
 * this test method's own first statement runs. A `-javaagent` gets ahead of that: the JVM runs it
 * before any application class loads at all, discovery included.
 */
class Ktor2ModuleTest {
    @Test
    fun `registration and dispatch through a real CIO server are tracked end to end`() {
        val registry = Ktor2TestAgent.registry
        val port = ServerSocket(0).use { it.localPort }

        val server =
            embeddedServer(CIO, port = port) {
                routing {
                    get("/checkout/{id}") { call.respondText("ok") }
                    post("/promo") { call.respondText("promo") }
                    route("/api") {
                        get("/orders/{id?}") { call.respondText("orders") }
                        get("/files/{path...}") { call.respondText("files") }
                    }
                    route("/any") {
                        handle { call.respondText("any") }
                    }
                }
            }
        server.start(wait = false)

        try {
            val client = HttpClient.newHttpClient()
            sendGet(client, port, "/checkout/42")
            sendGet(client, port, "/checkout/42")
            sendGet(client, port, "/api/orders")
            sendGet(client, port, "/api/files/a/b/c")
            sendGet(client, port, "/any")
            sendGet(client, port, "/nothing")

            val endpoints = registry.endpoints()
            val byIdentity = endpoints.associateBy { "${it.verb} ${it.routeTemplate}" }
            assertEquals(
                setOf("GET /checkout/{id}", "POST /promo", "GET /api/orders/{id?}", "GET /api/files/*", "* /any"),
                byIdentity.keys,
            )

            for (endpoint in endpoints) {
                assertEquals("ktor-2", endpoint.framework)
                assertEquals(EndpointDiscoverySource.REGISTRATION, endpoint.discoverySource)
                assertNotNull(endpoint.handlerClass, "handler class missing for ${endpoint.verbatimTemplate}")
                assertTrue(
                    endpoint.handlerClass!!.contains("Ktor2ModuleTest"),
                    "handler class ${endpoint.handlerClass} is not from this test",
                )
            }

            val deltasById = registry.computeDeltas(maxPerBatch = 10).flatMap { it.deltas }.associateBy { it.endpointId }
            assertEquals(2L, deltasById.getValue(byIdentity.getValue("GET /checkout/{id}").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(byIdentity.getValue("GET /api/orders/{id?}").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(byIdentity.getValue("GET /api/files/*").endpointId).hitsTotal)
            assertEquals(1L, deltasById.getValue(byIdentity.getValue("* /any").endpointId).hitsTotal)
            assertTrue(byIdentity.getValue("POST /promo").endpointId !in deltasById)

            assertTrue(registry.disabledModules().isEmpty())
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
            Ktor2TestAgent.uninstall()
        }
    }

    private fun sendGet(
        client: HttpClient,
        port: Int,
        path: String,
    ) {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
        client.send(request, HttpResponse.BodyHandlers.discarding())
    }
}
