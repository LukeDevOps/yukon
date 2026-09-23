package io.github.lukedevops.yukon.instrumentation.endpoints.jdkhttpserver

import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.github.lukedevops.yukon.bootstrap.YukonEndpoints
import io.github.lukedevops.yukon.instrumentation.endpoints.EndpointInstrumentation
import io.github.lukedevops.yukon.instrumentation.endpoints.jdkhttpserver.fixture.JavaHandlers
import io.github.lukedevops.yukon.instrumentation.endpoints.jdkhttpserver.fixture.kotlinLambda
import io.github.lukedevops.yukon.instrumentation.endpoints.jdkhttpserver.fixture.registerKotlinReference
import io.github.lukedevops.yukon.registry.EndpointRegistry
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HANDLE_DESCRIPTOR = "(Lcom/sun/net/httpserver/HttpExchange;)V"
private const val KOTLIN_HANDLERS = "io.github.lukedevops.yukon.instrumentation.endpoints.jdkhttpserver.fixture.KotlinHandlersKt"

/**
 * Proves ADR 0035 against the real JDK `HttpServer` and the real lambda factory: an endpoint whose
 * handler is a hidden class joins to the method the lambda calls.
 *
 * The hook retransforms `java.lang.invoke.InnerClassLambdaMetafactory` for the whole JVM, and
 * the endpoint advice only weaves `ServerImpl` as it first loads. So this class needs a JVM of its
 * own, which the module's `forkEvery = 1` gives it, and installs once for every test here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HiddenHandlerNamingTest {
    private lateinit var instrumentation: Instrumentation
    private lateinit var registry: EndpointRegistry
    private lateinit var endpointInstrumentation: EndpointInstrumentation
    private lateinit var transformer: ResettableClassFileTransformer
    private lateinit var spunBeforeHook: HttpHandler

    @BeforeAll
    fun installBeforeAnyServerLoads() {
        instrumentation = ByteBuddyAgent.install()
        spunBeforeHook = JavaHandlers.lambdaSpunEarly()
        registry = EndpointRegistry()
        endpointInstrumentation = EndpointInstrumentation(registry, listOf(JdkHttpServerModule()))
        transformer = endpointInstrumentation.install(instrumentation)
    }

    @AfterAll
    fun uninstall() {
        endpointInstrumentation.uninstall(instrumentation, transformer)
    }

    @Test
    fun `a hidden handler joins to the method its lambda calls, for every way a handler lambda is written`() {
        val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        try {
            server.createContext("/java-lambda", JavaHandlers.lambda())
            server.createContext("/java-capturing-lambda", JavaHandlers.capturingLambda("body"))
            server.createContext("/java-static-reference", JavaHandlers.staticReference())
            server.createContext("/java-bound-reference", JavaHandlers().boundReference())
            server.createContext("/kotlin-lambda", kotlinLambda())
            registerKotlinReference(server, "/kotlin-reference")
            JavaHandlers.register(server, "/built-elsewhere", kotlinLambda())
            server.createContext("/set-later").setHandler(JavaHandlers.staticReference())
            server.createContext("/spun-before-hook", spunBeforeHook)
            server.createContext("/interface-reference", JavaHandlers.interfaceReference(JavaHandlers.lambda()))
            server.start()
            val client = HttpClient.newHttpClient()
            get(client, server, "/java-lambda")
            get(client, server, "/kotlin-reference")

            val byRoute = registry.endpoints().associateBy { it.routeTemplate }
            val javaHandlers = JavaHandlers::class.java
            val kotlinHandlers = Class.forName(KOTLIN_HANDLERS)

            assertJoinsDeclaredMethod(byRoute.getValue("/java-lambda"), javaHandlers, "lambda\$lambda\$", HANDLE_DESCRIPTOR)
            assertJoinsDeclaredMethod(
                byRoute.getValue("/java-capturing-lambda"),
                javaHandlers,
                "lambda\$capturingLambda\$",
                "(Ljava/lang/String;Lcom/sun/net/httpserver/HttpExchange;)V",
            )
            assertJoinsDeclaredMethod(byRoute.getValue("/java-static-reference"), javaHandlers, "handleStatically", HANDLE_DESCRIPTOR)
            assertEquals("handleStatically", byRoute.getValue("/java-static-reference").handlerMethod)
            assertJoinsDeclaredMethod(byRoute.getValue("/java-bound-reference"), javaHandlers, "handle", HANDLE_DESCRIPTOR)
            assertEquals("handle", byRoute.getValue("/java-bound-reference").handlerMethod, "the bound receiver is not a parameter")
            assertJoinsDeclaredMethod(byRoute.getValue("/kotlin-lambda"), kotlinHandlers, "kotlinLambda\$lambda\$", HANDLE_DESCRIPTOR)
            assertJoinsDeclaredMethod(byRoute.getValue("/kotlin-reference"), kotlinHandlers, "handleFromKotlin", HANDLE_DESCRIPTOR)
            assertEquals("handleFromKotlin", byRoute.getValue("/kotlin-reference").handlerMethod)
            assertJoinsDeclaredMethod(byRoute.getValue("/built-elsewhere"), kotlinHandlers, "kotlinLambda\$lambda\$", HANDLE_DESCRIPTOR)
            assertJoinsDeclaredMethod(byRoute.getValue("/set-later"), javaHandlers, "handleStatically", HANDLE_DESCRIPTOR)
            assertNoJoin(byRoute.getValue("/spun-before-hook"), "a lambda class spun before the hook was not recorded, so it gets no join")
            assertNoJoin(
                byRoute.getValue("/interface-reference"),
                "a reference through an interface names an abstract method, and which method runs depends on the receiver",
            )

            val deltas = registry.computeDeltas(maxPerBatch = 20).flatMap { it.deltas }.associateBy { it.endpointId }
            assertEquals(1L, deltas.getValue(byRoute.getValue("/java-lambda").endpointId).hitsTotal)
            assertEquals(1L, deltas.getValue(byRoute.getValue("/kotlin-reference").endpointId).hitsTotal)
            assertTrue(registry.disabledModules().isEmpty())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a lambda for an interface outside the handler set is not recorded`() {
        val runnable = Runnable {}
        assertTrue(runnable.javaClass.isHidden)
        assertNull(YukonEndpoints.lambdaImplementation(runnable.javaClass), "only handler interfaces are recorded")
        val handler = kotlinLambda()
        assertNotNull(YukonEndpoints.lambdaImplementation(handler.javaClass), "the control: an HttpHandler lambda is recorded")

        // A lambda for a subinterface of HttpHandler is spun for that subinterface, which is not in the set.
        val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        try {
            server.createContext("/named-handler-lambda", JavaHandlers.namedHandlerLambda())
            val endpoint = registry.endpoints().single { it.routeTemplate == "/named-handler-lambda" }
            assertNoJoin(endpoint, "the lambda factory reported the subinterface, which no module named")
        } finally {
            server.stop(0)
        }
    }

    private fun get(
        client: HttpClient,
        server: HttpServer,
        path: String,
    ) {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:${server.address.port}$path")).GET().build()
        client.send(request, HttpResponse.BodyHandlers.discarding())
    }
}
