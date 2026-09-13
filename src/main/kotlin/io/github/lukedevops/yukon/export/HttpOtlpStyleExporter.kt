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
 * backoff, but only for failures that a retry can plausibly fix: a
 * connection or timeout error, a 5xx, a 408, or a 429. Any other 4xx fails
 * at once. The collector has already read the bytes and rejected them, so
 * resending the same bytes a moment later cannot change its answer; it only
 * burns the flush budget. If every attempt fails, the exception propagates
 * to the caller. The caller is expected to be [ExportScheduler]. It treats
 * the exception as a signal to leave the registry baseline untouched, and
 * retries the whole delta on the next flush.
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

    override fun exportStaticBaseline(baseline: StaticBaseline) {
        post("$endpoint/v1/yukon/static-baseline", ProtoPayloadCodec.encode(baseline))
    }

    private fun post(
        uri: String,
        body: ByteArray,
    ) {
        var backoff = initialBackoff
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            val status =
                try {
                    val request =
                        HttpRequest
                            .newBuilder(URI.create(uri))
                            .timeout(requestTimeout)
                            .header("Content-Type", "application/x-protobuf")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build()
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                } catch (e: Exception) {
                    lastError = e
                    null
                }
            if (status != null) {
                if (status in 200..299) return
                val failure = ExportFailedException("yukon: unexpected status $status from $uri", status)
                if (!isRetryable(status)) throw failure
                lastError = failure
            }
            if (attempt < maxAttempts) {
                Thread.sleep(backoff.toMillis())
                backoff = minOf(backoff.multipliedBy(2), maxBackoff)
            }
        }
        throw lastError ?: ExportFailedException("yukon: export to $uri failed")
    }

    /** 408 and 429 are the two 4xx codes that describe the server's state at that moment, not the request itself. */
    private fun isRetryable(status: Int): Boolean = status >= 500 || status == 408 || status == 429

    private companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

/** [statusCode] is null when the failure was not an HTTP response at all (for example, every attempt threw). */
class ExportFailedException(
    message: String,
    val statusCode: Int? = null,
) : RuntimeException(message)
