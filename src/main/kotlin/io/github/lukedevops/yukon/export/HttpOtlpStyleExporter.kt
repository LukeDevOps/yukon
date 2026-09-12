package io.github.lukedevops.yukon.export

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Sends both payload shapes to a collector at [endpoint] over plain HTTP/1.1.
 * This uses the JDK's built-in [HttpClient]. It avoids a shaded gRPC/Netty
 * dependency.
 *
 * Each send retries up to [maxAttempts] times, with capped exponential
 * backoff. If every attempt fails, the exception propagates to the caller.
 * The caller is expected to be [ExportScheduler]. It treats the exception as
 * a signal to leave the registry baseline untouched, and retries the whole
 * delta on the next flush.
 *
 * [httpClient]'s connect timeout and each request's [requestTimeout] are both
 * bounded by default. Without a timeout, a collector that accepts a
 * connection but never responds would block a send indefinitely. This can
 * happen if the collector is wedged, or if the network silently drops
 * packets. An indefinite block would stall [ExportScheduler]'s single flush
 * thread, including its liveness heartbeat, instead of failing into the
 * retry/backoff path above.
 */
class HttpOtlpStyleExporter(
    private val endpoint: String,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
    private val maxAttempts: Int = 5,
    private val initialBackoff: Duration = Duration.ofMillis(200),
    private val maxBackoff: Duration = Duration.ofSeconds(30),
    private val requestTimeout: Duration = DEFAULT_TIMEOUT,
) : Exporter {
    override fun exportDeltaBatch(batch: DeltaBatch) {
        post("$endpoint/v1/yukon/deltas", ProtoPayloadCodec.encode(batch))
    }

    override fun exportManifest(manifest: ProbeManifest) {
        post("$endpoint/v1/yukon/manifest", ProtoPayloadCodec.encode(manifest))
    }

    private fun post(
        uri: String,
        body: ByteArray,
    ) {
        var backoff = initialBackoff
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                val request =
                    HttpRequest
                        .newBuilder(URI.create(uri))
                        .timeout(requestTimeout)
                        .header("Content-Type", "application/x-protobuf")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
                if (response.statusCode() in 200..299) return
                lastError = ExportFailedException("yukon: unexpected status ${response.statusCode()} from $uri")
            } catch (e: Exception) {
                lastError = e
            }
            if (attempt < maxAttempts) {
                Thread.sleep(backoff.toMillis())
                backoff = minOf(backoff.multipliedBy(2), maxBackoff)
            }
        }
        throw lastError ?: ExportFailedException("yukon: export to $uri failed")
    }

    private companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

class ExportFailedException(
    message: String,
) : RuntimeException(message)
