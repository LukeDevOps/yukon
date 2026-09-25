package io.github.lukedevops.demo.collector

import com.google.protobuf.MessageLite
import io.github.lukedevops.yukon.proto.ClassLocation
import io.github.lukedevops.yukon.proto.DeltaBatch
import io.github.lukedevops.yukon.proto.KotlinKind
import io.github.lukedevops.yukon.proto.ProbeKind
import io.github.lukedevops.yukon.proto.ProbeLocation
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

    @Test
    fun `a file facade and a multi-file part print as their source file, and a multi-file facade carries a tag`() {
        fun probe(
            classId: Int,
            className: String,
        ) = ProbeLocation
            .newBuilder()
            .setClassId(classId)
            .setKind(ProbeKind.METHOD)
            .setClassName(className)
            .setMethodName("greet")
            .setMethodDescriptor("()V")
        fun location(
            classId: Int,
            kind: KotlinKind,
            sourceFile: String,
        ) = ClassLocation
            .newBuilder()
            .setClassId(classId)
            .setSuperClassName("java.lang.Object")
            .setSourceFile(sourceFile)
            .setKotlinKind(kind)
        val manifest =
            ProbeManifest
                .newBuilder()
                .setResource(resource("run-naming"))
                .addProbes(probe(0, "com.acme.naming.TextKt"))
                .addProbes(probe(1, "com.acme.naming.Words"))
                .addProbes(probe(2, "com.acme.naming.Words__HelloKt"))
                .addProbes(probe(3, "com.acme.naming.Plain"))
                .addClassLocations(location(0, KotlinKind.FILE_FACADE, "Text.kt"))
                .addClassLocations(location(1, KotlinKind.MULTIFILE_CLASS_FACADE, ""))
                .addClassLocations(location(2, KotlinKind.MULTIFILE_CLASS_PART, "Hello.kt"))
                .addClassLocations(location(3, KotlinKind.KOTLIN_CLASS, "Plain.kt"))
                .build()

        assertEquals(200, post("manifest", manifest))

        assertEquals("Text.kt", classText("com.acme.naming.TextKt"))
        assertEquals("greet (Text.kt)", methodText("com.acme.naming.TextKt", "greet", "()V"))
        assertEquals("greet (Text.kt:12)", methodText("com.acme.naming.TextKt", "greet", "()V", 12))
        assertEquals("Hello.kt", classText("com.acme.naming.Words__HelloKt"))
        assertEquals("com.acme.naming.Words (multi-file facade)", classText("com.acme.naming.Words"))
        assertEquals("com.acme.naming.Words#greet:1 (multi-file facade)", methodText("com.acme.naming.Words", "greet", "()V", 1))
        assertEquals("com.acme.naming.Plain", classText("com.acme.naming.Plain"))
        assertEquals("com.acme.naming.Plain#greet:3", methodText("com.acme.naming.Plain", "greet", "()V", 3))
        assertEquals("com.acme.naming.Unknown#greet", methodText("com.acme.naming.Unknown", "greet", "()V"))
    }
}
