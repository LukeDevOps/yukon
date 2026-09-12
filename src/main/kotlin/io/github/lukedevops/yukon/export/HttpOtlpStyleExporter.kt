package io.github.lukedevops.yukon.export

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Sends both payload shapes to a collector at [endpoint] over plain HTTP/1.1,
 * using the JDK's built-in [HttpClient] rather than a shaded gRPC/Netty
 * dependency. Each send is retried up to [maxAttempts] times with capped
 * exponential backoff before the failure is propagated to the caller, which
 * is expected to be [ExportScheduler]: it treats that exception as a signal
 * to leave the registry baseline untouched and retry the whole delta on the
 * next flush.
 */
class HttpOtlpStyleExporter(
    private val endpoint: String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val maxAttempts: Int = 5,
    private val initialBackoff: Duration = Duration.ofMillis(200),
    private val maxBackoff: Duration = Duration.ofSeconds(30),
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
}

class ExportFailedException(
    message: String,
) : RuntimeException(message)
