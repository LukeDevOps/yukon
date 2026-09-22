package io.github.lukedevops.yukon.testkit

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ExportScheduler
import io.github.lukedevops.yukon.export.HttpOtlpStyleExporter
import io.github.lukedevops.yukon.instrumentation.endpoints.EndpointInstrumentation
import io.github.lukedevops.yukon.instrumentation.endpoints.jdkhttpserver.JdkHttpServerModule
import io.github.lukedevops.yukon.registry.EndpointRegistry
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import org.junit.jupiter.api.Order
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves endpoint tracking end to end through [YukonTestCollector], over the real wire protocol,
 * against a real `com.sun.net.httpserver.HttpServer` instrumented by [JdkHttpServerModule].
 *
 * [EndpointInstrumentation.install] must run before `sun.net.httpserver`'s `ServerImpl`,
 * `HttpContextImpl`, and `ContextList` load for the first time in this JVM: a ByteBuddy
 * transformer only weaves advice into a class as it loads, never into one already loaded (see
 * `JdkHttpServerModuleTest` in `:endpoints-jdk-httpserver` for the same constraint proven against
 * the module directly). [YukonTestCollector] is itself backed by a JDK `HttpServer`, so
 * [YukonTestCollector.start] loads exactly those classes; so does every other test class in this
 * module that starts one. This class is pinned to run first in this module's test suite with a
 * class-level [Order] and this module's own `junit-platform.properties`
 * (`junit.jupiter.testclass.order.default`), mirroring the root project's
 * `YukonEndpointsSeamTest`. One consequence of running first: this test's own
 * [YukonTestCollector] is also instrumented, so its `/v1/yukon/...` contexts appear in
 * [EndpointRegistry.endpoints] alongside `/checkout` and `/promo`. That is expected and harmless;
 * this test only asserts on the two contexts it creates itself.
 */
@Order(Int.MIN_VALUE)
class EndpointEndToEndTest {
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedEndpointInstrumentation: EndpointInstrumentation? = null
    private var scheduler: ExportScheduler? = null
    private var collector: YukonTestCollector? = null
    private var appServer: HttpServer? = null

    @AfterTest
    fun tearDown() {
        scheduler?.stop()
        appServer?.stop(0)
        installedTransformer?.let { installedEndpointInstrumentation?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedEndpointInstrumentation = null
        collector?.close()
    }

    @Test
    fun `an endpoint's call count and never-called status are observable over the wire`() {
        val instrumentation = ByteBuddyAgent.install()
        val registry = EndpointRegistry()
        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(JdkHttpServerModule()))
        installedEndpointInstrumentation = endpointInstrumentation
        installedTransformer = endpointInstrumentation.install(instrumentation)

        val target = YukonTestCollector.start()
        collector = target

        val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        appServer = server
        server.createContext("/checkout", HttpHandler { exchange -> respond(exchange) })
        server.createContext("/promo", HttpHandler { exchange -> respond(exchange) })
        server.start()

        val client = HttpClient.newHttpClient()
        val port = server.address.port
        get(client, port, "/checkout")
        get(client, port, "/checkout")

        val config =
            AgentConfig.parse(
                "endpoint=${target.endpoint}," +
                    "flushIntervalSeconds=1," +
                    "serviceName=testkit-endpoint-e2e," +
                    "serviceInstanceId=e2e-endpoint-1",
            )
        val exporter = HttpOtlpStyleExporter(target.endpoint)
        val exportScheduler = ExportScheduler(config, TestResources.forConfig(config), ProbeRegistry(), registry, exporter)
        scheduler = exportScheduler
        exportScheduler.start()

        target.awaitEndpoint("*", "/checkout", Duration.ofSeconds(10))
        target.awaitNextFlush(Duration.ofSeconds(10))

        assertTrue(target.wasCalled("*", "/checkout"))
        assertEquals(2L, target.callCount("*", "/checkout"))
        assertTrue(target.neverCalled().any { it.routeTemplate == "/promo" })
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
