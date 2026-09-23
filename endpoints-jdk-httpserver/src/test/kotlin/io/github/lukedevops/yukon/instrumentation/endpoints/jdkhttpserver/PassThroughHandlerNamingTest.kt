package io.github.lukedevops.yukon.instrumentation.endpoints.jdkhttpserver

import com.example.classsam.lambda
import com.example.classsam.privateReference
import com.example.classsam.reference
import com.example.classsam.register
import com.example.classsam.wrapped
import com.sun.net.httpserver.HttpServer
import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.instrumentation.YukonInstrumentation
import io.github.lukedevops.yukon.instrumentation.endpoints.EndpointInstrumentation
import io.github.lukedevops.yukon.instrumentation.endpoints.HandlerForwarders
import io.github.lukedevops.yukon.registry.EndpointRegistry
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.lang.instrument.Instrumentation
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val HANDLE_DESCRIPTOR = "(Lcom/sun/net/httpserver/HttpExchange;)V"
private const val HANDLERS = "com.example.classsam.HandlersKt"

/**
 * Proves the forwarder table of ADR 0035 against the real JDK `HttpServer`, with the method tier
 * installed: a handler that is a kotlinc reference class, compiled with class-based SAM conversion,
 * joins to the function it calls, not to the reference class's own `handle`.
 *
 * The reference class is synthetic, so the method tier never analyses it. Its entry is written when
 * `HandlersKt`, which creates it, is transformed. So `HandlersKt` must load after the method tier is
 * installed, which the module's `forkEvery = 1` and this class's [BeforeAll] give it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PassThroughHandlerNamingTest {
    private lateinit var instrumentation: Instrumentation
    private lateinit var probes: ProbeRegistry
    private lateinit var yukon: YukonInstrumentation
    private lateinit var yukonTransformer: ResettableClassFileTransformer
    private lateinit var endpoints: EndpointRegistry
    private lateinit var endpointInstrumentation: EndpointInstrumentation
    private lateinit var endpointTransformer: ResettableClassFileTransformer

    @BeforeAll
    fun installBothTiersBeforeTheFixtureLoads() {
        instrumentation = ByteBuddyAgent.install()
        val module = JdkHttpServerModule()
        val forwarders = HandlerForwarders(module.handlerInterfaces)
        probes = ProbeRegistry()
        yukon = YukonInstrumentation(AgentConfig.parse("includePackages=com.example.classsam"), probes, handlerForwarders = forwarders)
        yukonTransformer = yukon.install(instrumentation)
        endpoints = EndpointRegistry()
        endpointInstrumentation = EndpointInstrumentation(endpoints, listOf(module), handlerForwarders = forwarders)
        endpointTransformer = endpointInstrumentation.install(instrumentation)
    }

    @AfterAll
    fun uninstall() {
        endpointInstrumentation.uninstall(instrumentation, endpointTransformer)
        yukon.uninstall(instrumentation, yukonTransformer)
    }

    @Test
    fun `a reference class handler joins to the function it calls, and other shapes keep their own name`() {
        val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        try {
            server.createContext("/reference", reference())
            server.createContext("/secret", privateReference())
            register(server)
            server.createContext("/set-later").setHandler(reference())
            server.createContext("/lambda", lambda())
            server.createContext("/wrapped", wrapped())
            server.start()
            val request = HttpRequest.newBuilder(URI.create("http://localhost:${server.address.port}/reference")).GET().build()
            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())

            val byRoute = endpoints.endpoints().associateBy { it.routeTemplate }
            val handlers = Class.forName(HANDLERS)
            assertJoinsDeclaredMethod(byRoute.getValue("/reference"), handlers, "handleOrder", HANDLE_DESCRIPTOR)
            assertJoinsDeclaredMethod(byRoute.getValue("/secret"), handlers, "handleSecret", HANDLE_DESCRIPTOR)
            assertJoinsDeclaredMethod(byRoute.getValue("/order"), handlers, "handleOrder", HANDLE_DESCRIPTOR)
            assertJoinsDeclaredMethod(byRoute.getValue("/set-later"), handlers, "handleOrder", HANDLE_DESCRIPTOR)
            assertEquals("handleOrder", byRoute.getValue("/reference").handlerMethod)
            assertEquals("handleSecret", byRoute.getValue("/secret").handlerMethod, "the entry names the function behind access$")

            // A lambda compiled to a class is probed in its own right, so its handle keeps the join.
            assertJoinsDeclaredMethod(byRoute.getValue("/lambda"), Class.forName("$HANDLERS\$lambda\$1"), "handle", HANDLE_DESCRIPTOR)
            // A $sam$ wrapper only calls the function value it holds, which reaches no probed method.
            val wrapper = Class.forName("$HANDLERS\$sam\$com_sun_net_httpserver_HttpHandler\$0")
            assertJoinsDeclaredMethod(byRoute.getValue("/wrapped"), wrapper, "handle", HANDLE_DESCRIPTOR)

            val methodProbes =
                probes
                    .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                    .probes
                    .filter { it.kind == ProbeKind.METHOD && it.className == HANDLERS }
                    .map { it.methodName }
            assertTrue("handleOrder" in methodProbes && "handleSecret" in methodProbes, "each join names a probed method: $methodProbes")
            assertEquals(1L, hitsOf(byRoute.getValue("/reference").endpointId))
        } finally {
            server.stop(0)
        }
    }

    private fun hitsOf(endpointId: Int): Long =
        endpoints
            .computeDeltas(maxPerBatch = 20)
            .flatMap { it.deltas }
            .single { it.endpointId == endpointId }
            .hitsTotal
}
