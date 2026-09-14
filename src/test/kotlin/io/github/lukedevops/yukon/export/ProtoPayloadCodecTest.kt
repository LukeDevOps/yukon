package io.github.lukedevops.yukon.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.github.lukedevops.yukon.proto.DeltaBatch as ProtoDeltaBatch
import io.github.lukedevops.yukon.proto.EndpointDiscoverySource as ProtoEndpointDiscoverySource
import io.github.lukedevops.yukon.proto.EndpointLocation as ProtoEndpointLocation
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
                        ProbeDelta(classId = 0, probeIndex = 1, kind = ProbeKind.METHOD, firstSeenAt = 1000L, hitsTotal = 5L),
                        ProbeDelta(classId = 0, probeIndex = 2, kind = ProbeKind.BRANCH, firstSeenAt = 1200L, hitsTotal = 1L),
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
        assertEquals(5L, decoded.deltasList[0].hitsTotal)
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

    @Test
    fun `decodes a delta batch back into the same values it was encoded from`() {
        val batch =
            DeltaBatch(
                resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "prod"),
                deltas =
                    listOf(
                        ProbeDelta(classId = 0, probeIndex = 1, kind = ProbeKind.METHOD, firstSeenAt = 1000L, hitsTotal = 5L),
                        ProbeDelta(classId = 0, probeIndex = 2, kind = ProbeKind.BRANCH, firstSeenAt = 1200L, hitsTotal = 1L),
                    ),
            )

        val decoded = ProtoPayloadCodec.decodeDeltaBatch(ProtoPayloadCodec.encode(batch))

        assertEquals(batch, decoded)
    }

    @Test
    fun `decodes a delta batch with null optional resource fields`() {
        val batch =
            DeltaBatch(
                resource = ResourceAttributes("checkout", serviceVersion = null, serviceInstanceId = "i-1", environment = null),
                deltas = emptyList(),
            )

        val decoded = ProtoPayloadCodec.decodeDeltaBatch(ProtoPayloadCodec.encode(batch))

        assertEquals(batch, decoded)
    }

    @Test
    fun `decodes a probe manifest back into the same values it was encoded from, including skipped classes`() {
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
                skippedClasses =
                    listOf(
                        SkippedClass(
                            className = "com.example.Skipped",
                            reason = "annotation not supported on TYPE",
                            skippedAt = 1000L,
                        ),
                    ),
            )

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        assertEquals(manifest, decoded)
    }

    @Test
    fun `decodes a probe manifest with a null service version`() {
        val manifest = ProbeManifest(serviceName = "checkout", serviceVersion = null, probes = emptyList())

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        assertEquals(manifest, decoded)
    }

    @Test
    fun `a probe manifest's service instance id round-trips through the wire`() {
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = "1.0.0",
                probes = emptyList(),
                serviceInstanceId = "instance-1",
            )

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        assertEquals("instance-1", decoded.serviceInstanceId)
        assertEquals(manifest, decoded)
    }

    @Test
    fun `encodes and decodes a static baseline with declared classes and methods`() {
        val baseline =
            StaticBaseline(
                resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "prod"),
                declaredClasses =
                    listOf(
                        DeclaredClass(
                            className = "com.example.Foo",
                            methods =
                                listOf(
                                    DeclaredMethod(methodName = "bar", methodDescriptor = "()V"),
                                    DeclaredMethod(methodName = "baz", methodDescriptor = "(I)Z"),
                                ),
                        ),
                    ),
                scannedAt = 1000L,
            )

        val decoded = ProtoPayloadCodec.decodeStaticBaseline(ProtoPayloadCodec.encode(baseline))

        assertEquals(baseline, decoded)
    }

    @Test
    fun `encodes and decodes a static baseline's statically-unsafe and unreadable classes`() {
        val baseline =
            StaticBaseline(
                resource = ResourceAttributes("checkout", null, "instance-1", null),
                declaredClasses = emptyList(),
                staticallyUnsafeClasses =
                    listOf(StaticallyUnsafeClass("com.example.Unsafe", "@kotlin.jvm.JvmName is not legal on TYPE")),
                unreadableClasses =
                    listOf(UnreadableClass("com.example.Corrupt", "unexpected end of ZLIB input stream")),
                scannedAt = 2000L,
            )

        val decoded = ProtoPayloadCodec.decodeStaticBaseline(ProtoPayloadCodec.encode(baseline))

        assertEquals(baseline, decoded)
    }

    @Test
    fun `encodes and decodes a static baseline's unprobed classes and chunk position`() {
        val baseline =
            StaticBaseline(
                resource = ResourceAttributes("checkout", null, "instance-1", null),
                declaredClasses = emptyList(),
                unprobedClasses = listOf(UnprobedClass("com.example.Marker", "no concrete methods to probe")),
                scannedAt = 4000L,
                chunkIndex = 2,
                chunkCount = 5,
            )

        val decoded = ProtoPayloadCodec.decodeStaticBaseline(ProtoPayloadCodec.encode(baseline))

        assertEquals(baseline, decoded)
    }

    @Test
    fun `encodes an empty static baseline`() {
        val baseline =
            StaticBaseline(
                resource = ResourceAttributes("checkout", null, "instance-1", null),
                declaredClasses = emptyList(),
                scannedAt = 3000L,
            )

        val decoded = ProtoPayloadCodec.decodeStaticBaseline(ProtoPayloadCodec.encode(baseline))

        assertEquals(baseline, decoded)
    }

    @Test
    fun `encodes skipped classes on the manifest`() {
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = null,
                probes = emptyList(),
                skippedClasses =
                    listOf(
                        SkippedClass(
                            className = "com.example.Foo",
                            reason = "annotation not supported on TYPE",
                            skippedAt = 1000L,
                        ),
                    ),
            )

        val decoded = ProtoProbeManifest.parseFrom(ProtoPayloadCodec.encode(manifest))

        val skipped = decoded.skippedClassesList.single()
        assertEquals("com.example.Foo", skipped.className)
        assertEquals("annotation not supported on TYPE", skipped.reason)
        assertEquals(1000L, skipped.skippedAt)
    }

    @Test
    fun `encodes a delta batch's endpoint deltas that round-trip through the generated protobuf schema`() {
        val batch =
            DeltaBatch(
                resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "prod"),
                deltas = emptyList(),
                endpointDeltas =
                    listOf(
                        EndpointDelta(endpointId = 0, firstSeenAt = 1000L, hitsTotal = 7L),
                        EndpointDelta(endpointId = 1, firstSeenAt = 1500L, hitsTotal = 0L),
                    ),
            )

        val decoded = ProtoDeltaBatch.parseFrom(ProtoPayloadCodec.encode(batch))

        assertEquals(2, decoded.endpointDeltasList.size)
        assertEquals(0, decoded.endpointDeltasList[0].endpointId)
        assertEquals(1000L, decoded.endpointDeltasList[0].firstSeenAt)
        assertEquals(7L, decoded.endpointDeltasList[0].hitsTotal)
        assertEquals(1, decoded.endpointDeltasList[1].endpointId)
    }

    @Test
    fun `decodes a delta batch's endpoint deltas back into the same values it was encoded from`() {
        val batch =
            DeltaBatch(
                resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "prod"),
                deltas = emptyList(),
                endpointDeltas = listOf(EndpointDelta(endpointId = 0, firstSeenAt = 1000L, hitsTotal = 7L)),
            )

        val decoded = ProtoPayloadCodec.decodeDeltaBatch(ProtoPayloadCodec.encode(batch))

        assertEquals(batch, decoded)
    }

    @Test
    fun `a delta batch with no endpoint deltas decodes to an empty list, matching an old payload`() {
        val batch =
            DeltaBatch(
                resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "prod"),
                deltas = emptyList(),
            )

        val decoded = ProtoPayloadCodec.decodeDeltaBatch(ProtoPayloadCodec.encode(batch))

        assertTrue(decoded.endpointDeltas.isEmpty())
        assertEquals(batch, decoded)
    }

    @Test
    fun `encodes and decodes a manifest endpoint with a full handler join`() {
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = "1.0.0",
                probes = emptyList(),
                endpoints =
                    listOf(
                        EndpointLocation(
                            endpointId = 0,
                            verb = "GET",
                            routeTemplate = "/checkout/{id}",
                            verbatimTemplate = "/checkout/{id:[0-9]+}",
                            framework = "spring-webmvc-6",
                            discoverySource = EndpointDiscoverySource.REGISTRATION,
                            handlerClass = "com.example.CheckoutController",
                            handlerMethod = "get",
                            handlerDescriptor = "(Ljava/lang/String;)Lorg/springframework/http/ResponseEntity;",
                        ),
                    ),
            )

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        assertEquals(manifest, decoded)
    }

    @Test
    fun `encodes and decodes a manifest endpoint with only the handler class known`() {
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = "1.0.0",
                probes = emptyList(),
                endpoints =
                    listOf(
                        EndpointLocation(
                            endpointId = 1,
                            verb = "POST",
                            routeTemplate = "/promo",
                            verbatimTemplate = "/promo",
                            framework = "ktor-3",
                            discoverySource = EndpointDiscoverySource.DISPATCH,
                            handlerClass = "com.example.PromoHandler",
                        ),
                    ),
            )

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        assertEquals(manifest, decoded)
    }

    @Test
    fun `encodes and decodes a manifest endpoint with no handler join at all`() {
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = "1.0.0",
                probes = emptyList(),
                endpoints =
                    listOf(
                        EndpointLocation(
                            endpointId = 2,
                            verb = "*",
                            routeTemplate = "/status",
                            verbatimTemplate = "/status",
                            framework = "jdk-httpserver",
                            discoverySource = EndpointDiscoverySource.DISPATCH,
                        ),
                    ),
            )

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        assertEquals(manifest, decoded)
    }

    @Test
    fun `optional handler fields are absent on the wire when null`() {
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = null,
                probes = emptyList(),
                endpoints =
                    listOf(
                        EndpointLocation(
                            endpointId = 0,
                            verb = "GET",
                            routeTemplate = "/status",
                            verbatimTemplate = "/status",
                            framework = "jdk-httpserver",
                            discoverySource = EndpointDiscoverySource.DISPATCH,
                        ),
                    ),
            )

        val decoded = ProtoProbeManifest.parseFrom(ProtoPayloadCodec.encode(manifest))

        val endpoint = decoded.endpointsList.single()
        assertFalse(endpoint.hasHandlerClass())
        assertFalse(endpoint.hasHandlerMethod())
        assertFalse(endpoint.hasHandlerDescriptor())
    }

    @Test
    fun `encodes and decodes a manifest's disabled endpoint modules`() {
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = null,
                probes = emptyList(),
                disabledEndpointModules =
                    listOf(
                        DisabledEndpointModule(
                            module = "spring-webmvc-6",
                            reason = "linkage failure against an unexpected framework version",
                            disabledAt = 2000L,
                        ),
                    ),
            )

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        assertEquals(manifest, decoded)

        val reparsed = ProtoProbeManifest.parseFrom(ProtoPayloadCodec.encode(manifest))
        val disabled = reparsed.disabledEndpointModulesList.single()
        assertEquals("spring-webmvc-6", disabled.module)
        assertEquals("linkage failure against an unexpected framework version", disabled.reason)
        assertEquals(2000L, disabled.disabledAt)
    }

    @Test
    fun `a manifest with no endpoint fields decodes to empty lists, matching an old payload`() {
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = null,
                probes = emptyList(),
            )

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        assertTrue(decoded.endpoints.isEmpty())
        assertTrue(decoded.disabledEndpointModules.isEmpty())
        assertEquals(manifest, decoded)
    }

    @Test
    fun `an endpoint with an unspecified discovery source fails to decode`() {
        val wireManifest =
            ProtoProbeManifest
                .newBuilder()
                .setServiceName("checkout")
                .addEndpoints(
                    ProtoEndpointLocation
                        .newBuilder()
                        .setEndpointId(0)
                        .setVerb("GET")
                        .setRouteTemplate("/status")
                        .setVerbatimTemplate("/status")
                        .setFramework("jdk-httpserver")
                        .setDiscoverySource(ProtoEndpointDiscoverySource.ENDPOINT_DISCOVERY_SOURCE_UNSPECIFIED)
                        .build(),
                ).build()

        assertFailsWith<IllegalArgumentException> {
            ProtoPayloadCodec.decodeProbeManifest(wireManifest.toByteArray())
        }
    }
}
