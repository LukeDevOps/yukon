package io.github.lukedevops.demo.collector

import com.google.protobuf.MessageLite
import io.github.lukedevops.yukon.proto.DeltaBatch
import io.github.lukedevops.yukon.proto.ProbeManifest
import io.github.lukedevops.yukon.proto.ResourceAttributes
import io.github.lukedevops.yukon.proto.StaticBaseline
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StubCollectorTest {
    private val server = startStubCollector(0)
    private val client = HttpClient.newHttpClient()

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    private fun resource(runId: String): ResourceAttributes =
        ResourceAttributes
            .newBuilder()
            .setServiceName("svc")
            .setServiceInstanceId("pinned")
            .setRunId(runId)
            .build()

    private fun post(
        path: String,
        payload: MessageLite,
    ): Int =
        client
            .send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:${server.address.port}/v1/yukon/$path"))
                    .header("Content-Type", "application/x-protobuf")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload.toByteArray()))
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            ).statusCode()

    @Test
    fun `a delta batch with an empty run id is answered 400, and one with a run id 200`() {
        assertEquals(400, post("deltas", DeltaBatch.newBuilder().setResource(resource("")).build()))
        assertEquals(200, post("deltas", DeltaBatch.newBuilder().setResource(resource("run-1")).build()))
    }

    @Test
    fun `a manifest with an empty run id is answered 400, and one with a run id 200`() {
        assertEquals(400, post("manifest", ProbeManifest.newBuilder().setResource(resource("")).build()))
        assertEquals(200, post("manifest", ProbeManifest.newBuilder().setResource(resource("run-1")).build()))
    }

    @Test
    fun `a static baseline with an empty run id is answered 400, and one with a run id 200`() {
        val baseline = StaticBaseline.newBuilder().setChunkCount(1)
        assertEquals(400, post("static-baseline", baseline.setResource(resource("")).build()))
        assertEquals(200, post("static-baseline", baseline.setResource(resource("run-1")).build()))
    }
}
