package io.github.lukedevops.yukon.export

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpOtlpStyleExporterTest {
    private var server: HttpServer? = null
    private val requestCount = AtomicInteger(0)
    private val requestedPaths = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    /** [handler] receives the 1-based number of this request and returns the status to send back. */
    private fun startServer(handler: (requestNumber: Int) -> Int): String {
        val httpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        httpServer.createContext("/") { exchange ->
            requestedPaths += exchange.requestURI.path
            val status = handler(requestCount.incrementAndGet())
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        httpServer.start()
        server = httpServer
        return "http://localhost:${httpServer.address.port}"
    }

    private fun exporterFor(endpoint: String) =
        HttpOtlpStyleExporter(
            endpoint = endpoint,
            maxAttempts = 5,
            initialBackoff = Duration.ofMillis(1),
            maxBackoff = Duration.ofMillis(10),
        )

    @Test
    fun `posts a delta batch to the deltas endpoint`() {
        val endpoint = startServer { 200 }
        val exporter = exporterFor(endpoint)
        val batch = DeltaBatch(ResourceAttributes("checkout", "1.0.0", "i-1", "test"), emptyList())

        exporter.exportDeltaBatch(batch)

        assertEquals(1, requestCount.get())
        assertEquals(listOf("/v1/yukon/deltas"), requestedPaths)
    }

    @Test
    fun `posts a manifest to the manifest endpoint`() {
        val endpoint = startServer { 200 }
        val exporter = exporterFor(endpoint)
        val manifest = ProbeManifest("checkout", "1.0.0", emptyList())

        exporter.exportManifest(manifest)

        assertEquals(listOf("/v1/yukon/manifest"), requestedPaths)
    }

    @Test
    fun `retries a transient server error and succeeds once the server recovers`() {
        val endpoint = startServer { requestNumber -> if (requestNumber < 3) 503 else 200 }
        val exporter = exporterFor(endpoint)

        exporter.exportDeltaBatch(DeltaBatch(ResourceAttributes("checkout", null, "i-1", null), emptyList()))

        assertEquals(3, requestCount.get())
    }

    @Test
    fun `gives up after exhausting retries and propagates the failure`() {
        val endpoint = startServer { 503 }
        val exporter = exporterFor(endpoint)

        assertFailsWith<Exception> {
            exporter.exportDeltaBatch(DeltaBatch(ResourceAttributes("checkout", null, "i-1", null), emptyList()))
        }
        assertEquals(5, requestCount.get())
    }
}
