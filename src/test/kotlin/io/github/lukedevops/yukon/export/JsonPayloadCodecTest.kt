package io.github.lukedevops.yukon.export

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonPayloadCodecTest {

    @Test
    fun `encodes a delta batch with resource attributes and deltas`() {
        val batch = DeltaBatch(
            resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "prod"),
            deltas = listOf(ProbeDelta(classId = 0, probeIndex = 1, kind = ProbeKind.METHOD, firstSeenAt = 1000L, hitsSinceLastFlush = 5L)),
        )

        val json = String(JsonPayloadCodec.encode(batch), StandardCharsets.UTF_8)

        assertEquals(
            "{\"resource\":{\"serviceName\":\"checkout\",\"serviceVersion\":\"1.0.0\"," +
                "\"serviceInstanceId\":\"instance-1\",\"environment\":\"prod\"}," +
                "\"deltas\":[{\"classId\":0,\"probeIndex\":1,\"kind\":\"METHOD\"," +
                "\"firstSeenAt\":1000,\"hitsSinceLastFlush\":5}]}",
            json,
        )
    }

    @Test
    fun `null resource fields render as JSON null`() {
        val batch = DeltaBatch(
            resource = ResourceAttributes("checkout", serviceVersion = null, serviceInstanceId = "i-1", environment = null),
            deltas = emptyList(),
        )

        val json = String(JsonPayloadCodec.encode(batch), StandardCharsets.UTF_8)

        assertEquals(
            "{\"resource\":{\"serviceName\":\"checkout\",\"serviceVersion\":null," +
                "\"serviceInstanceId\":\"i-1\",\"environment\":null},\"deltas\":[]}",
            json,
        )
    }

    @Test
    fun `quotes and backslashes in string fields are escaped`() {
        val manifest = ProbeManifest(
            serviceName = "checkout",
            serviceVersion = null,
            probes = listOf(
                ProbeLocation(
                    classId = 0,
                    probeIndex = 0,
                    kind = ProbeKind.METHOD,
                    className = "com.example.Foo\$\"weird\"\\Name",
                    methodName = "bar",
                    methodDescriptor = "()V",
                    line = 10,
                    branchIndex = null,
                ),
            ),
        )

        val json = String(JsonPayloadCodec.encode(manifest), StandardCharsets.UTF_8)

        assertEquals(
            "{\"serviceName\":\"checkout\",\"serviceVersion\":null,\"probes\":[{\"classId\":0,\"probeIndex\":0," +
                "\"kind\":\"METHOD\",\"className\":\"com.example.Foo\$\\\"weird\\\"\\\\Name\"," +
                "\"methodName\":\"bar\",\"methodDescriptor\":\"()V\",\"line\":10,\"branchIndex\":null}]}",
            json,
        )
    }
}
