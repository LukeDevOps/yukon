package io.github.lukedevops.yukon.export

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpOtlpStyleExporterTest {
    private var server: HttpServer? = null
    private var rawSocket: ServerSocket? = null
    private val requestCount = AtomicInteger(0)
    private val requestedPaths = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        rawSocket?.close()
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
    fun `posts a static baseline to the static-baseline endpoint`() {
        val endpoint = startServer { 200 }
        val exporter = exporterFor(endpoint)
        val baseline =
            StaticBaseline(
                resource = ResourceAttributes("checkout", "1.0.0", "i-1", "test"),
                declaredClasses = emptyList(),
                scannedAt = 1000L,
            )

        exporter.exportStaticBaseline(baseline)

        assertEquals(listOf("/v1/yukon/static-baseline"), requestedPaths)
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

    @Test
    fun `a per-request timeout bounds how long a hung collector can block a flush`() {
        // A raw socket that accepts the connection but never writes a response. This stands in
        // for a collector that is up but wedged, or a network path that silently black-holes
        // traffic. A plain "server returned an error status" test cannot exercise this case.
        // Without a request timeout wired into the HttpRequest, this call would hang
        // indefinitely instead of failing into the existing retry/backoff path.
        val socket = ServerSocket(0)
        rawSocket = socket
        thread(isDaemon = true) {
            try {
                while (!socket.isClosed) socket.accept()
            } catch (_: IOException) {
                // Expected once tearDown closes the socket.
            }
        }
        val endpoint = "http://localhost:${socket.localPort}"
        val exporter =
            HttpOtlpStyleExporter(
                endpoint = endpoint,
                maxAttempts = 2,
                initialBackoff = Duration.ofMillis(1),
                maxBackoff = Duration.ofMillis(1),
                requestTimeout = Duration.ofMillis(200),
            )

        val elapsed =
            measureTimeMillis {
                assertFailsWith<Exception> {
                    exporter.exportDeltaBatch(DeltaBatch(ResourceAttributes("checkout", null, "i-1", null), emptyList()))
                }
            }

        assertTrue(elapsed < 5_000, "expected the request timeout to bound the failure, took ${elapsed}ms")
    }
}
