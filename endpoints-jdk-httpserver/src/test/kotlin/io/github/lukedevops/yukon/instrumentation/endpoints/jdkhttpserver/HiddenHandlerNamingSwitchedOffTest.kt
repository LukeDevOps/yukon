package io.github.lukedevops.yukon.instrumentation.endpoints.jdkhttpserver

import com.sun.net.httpserver.HttpServer
import io.github.lukedevops.yukon.instrumentation.endpoints.EndpointInstrumentation
import io.github.lukedevops.yukon.instrumentation.endpoints.LambdaFactoryHook
import io.github.lukedevops.yukon.instrumentation.endpoints.LambdaFactoryShape
import io.github.lukedevops.yukon.instrumentation.endpoints.jdkhttpserver.fixture.JavaHandlers
import io.github.lukedevops.yukon.instrumentation.endpoints.jdkhttpserver.fixture.kotlinLambda
import io.github.lukedevops.yukon.registry.EndpointRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.logging.Level as JulLevel
import java.util.logging.Logger as JulLogger

/**
 * Proves that the lambda factory hook switches itself off on a JDK whose lambda factory does not
 * have the shape it reads. The test simulates that JDK with a shape that names a field the factory
 * lacks. The hook then logs one line and installs nothing. Every endpoint is registered and counted
 * as usual, and a hidden handler gets no join, which is the behaviour without the hook.
 *
 * It needs a JVM of its own for the same reasons as [HiddenHandlerNamingTest].
 */
class HiddenHandlerNamingSwitchedOffTest {
    @Test
    fun `a lambda factory without the expected shape leaves hidden handlers unnamed and breaks nothing`() {
        val records = mutableListOf<LogRecord>()
        val capture =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() {}

                override fun close() {}
            }
        val julLogger = JulLogger.getLogger(LambdaFactoryHook::class.java.name)
        julLogger.addHandler(capture)
        julLogger.level = JulLevel.ALL

        val instrumentation = ByteBuddyAgent.install()
        val registry = EndpointRegistry()
        val withoutImplInfo = LambdaFactoryShape.JDK.copy(implInfoField = "implInfoThatThisJdkLacks")
        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(JdkHttpServerModule()), withoutImplInfo)
        val transformer = endpointInstrumentation.install(instrumentation)

        val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        try {
            server.createContext("/kotlin-lambda", kotlinLambda())
            server.createContext("/java-static-reference", JavaHandlers.staticReference())
            server.start()
            val client = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder(URI.create("http://localhost:${server.address.port}/kotlin-lambda")).GET().build()
            client.send(request, HttpResponse.BodyHandlers.discarding())

            val byRoute = registry.endpoints().associateBy { it.routeTemplate }
            assertEquals(setOf("/kotlin-lambda", "/java-static-reference"), byRoute.keys)
            assertNoJoin(byRoute.getValue("/kotlin-lambda"), "the hook is off, so a hidden handler gets no join")
            assertNoJoin(byRoute.getValue("/java-static-reference"), "the hook is off, so a hidden handler gets no join")
            val deltas = registry.computeDeltas(maxPerBatch = 10).flatMap { it.deltas }
            assertEquals(1L, deltas.single { it.endpointId == byRoute.getValue("/kotlin-lambda").endpointId }.hitsTotal)
            assertTrue(registry.disabledModules().isEmpty(), "the endpoint module itself stays on")

            assertEquals(1, records.size, "one line, and only one: ${records.map { it.message }}")
            assertTrue("implInfoThatThisJdkLacks" in records.single().message, records.single().message)
        } finally {
            server.stop(0)
            endpointInstrumentation.uninstall(instrumentation, transformer)
            julLogger.removeHandler(capture)
        }
    }
}
