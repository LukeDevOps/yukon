package io.github.lukedevops.yukon.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.github.lukedevops.yukon.proto.DeltaBatch as ProtoDeltaBatch
import io.github.lukedevops.yukon.proto.ProbeKind as ProtoProbeKind
import io.github.lukedevops.yukon.proto.ProbeManifest as ProtoProbeManifest

class ProtoPayloadCodecTest {
    @Test
    fun `encodes a delta batch that round-trips through the generated protobuf schema`() {
        val batch =
            DeltaBatch(
                resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "prod"),
                deltas =
                    listOf(
                        ProbeDelta(classId = 0, probeIndex = 1, kind = ProbeKind.METHOD, firstSeenAt = 1000L, hitsSinceLastFlush = 5L),
                        ProbeDelta(classId = 0, probeIndex = 2, kind = ProbeKind.BRANCH, firstSeenAt = 1200L, hitsSinceLastFlush = 1L),
                    ),
            )

        val decoded = ProtoDeltaBatch.parseFrom(ProtoPayloadCodec.encode(batch))

        assertEquals("checkout", decoded.resource.serviceName)
        assertEquals("1.0.0", decoded.resource.serviceVersion)
        assertEquals("instance-1", decoded.resource.serviceInstanceId)
        assertEquals("prod", decoded.resource.environment)
        assertEquals(2, decoded.deltasList.size)
        assertEquals(0, decoded.deltasList[0].classId)
        assertEquals(1, decoded.deltasList[0].probeIndex)
        assertEquals(ProtoProbeKind.METHOD, decoded.deltasList[0].kind)
        assertEquals(1000L, decoded.deltasList[0].firstSeenAt)
        assertEquals(5L, decoded.deltasList[0].hitsSinceLastFlush)
        assertEquals(ProtoProbeKind.BRANCH, decoded.deltasList[1].kind)
    }

    @Test
    fun `omits optional resource fields when null`() {
        val batch =
            DeltaBatch(
                resource = ResourceAttributes("checkout", serviceVersion = null, serviceInstanceId = "i-1", environment = null),
                deltas = emptyList(),
            )

        val decoded = ProtoDeltaBatch.parseFrom(ProtoPayloadCodec.encode(batch))

        assertFalse(decoded.resource.hasServiceVersion())
        assertFalse(decoded.resource.hasEnvironment())
        assertTrue(decoded.deltasList.isEmpty())
    }

    @Test
    fun `encodes a probe manifest that round-trips through the generated protobuf schema`() {
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = "1.0.0",
                probes =
                    listOf(
                        ProbeLocation(
                            classId = 0,
                            probeIndex = 0,
                            kind = ProbeKind.METHOD,
                            className = "com.example.Foo",
                            methodName = "bar",
                            methodDescriptor = "()V",
                            line = 10,
                            branchIndex = null,
                        ),
                        ProbeLocation(
                            classId = 0,
                            probeIndex = 1,
                            kind = ProbeKind.BRANCH,
                            className = "com.example.Foo",
                            methodName = "bar",
                            methodDescriptor = "()V",
                            line = 12,
                            branchIndex = 3,
                        ),
                    ),
            )

        val decoded = ProtoProbeManifest.parseFrom(ProtoPayloadCodec.encode(manifest))

        assertEquals("checkout", decoded.serviceName)
        assertTrue(decoded.hasServiceVersion())
        assertEquals("1.0.0", decoded.serviceVersion)
        assertEquals(2, decoded.probesList.size)
        assertFalse(decoded.probesList[0].hasBranchIndex())
        assertTrue(decoded.probesList[1].hasBranchIndex())
        assertEquals(3, decoded.probesList[1].branchIndex)
        assertEquals(ProtoProbeKind.BRANCH, decoded.probesList[1].kind)
    }

    @Test
    fun `omits optional manifest service version and probe branch index when null`() {
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = null,
                probes = emptyList(),
            )

        val decoded = ProtoProbeManifest.parseFrom(ProtoPayloadCodec.encode(manifest))

        assertFalse(decoded.hasServiceVersion())
    }
}
