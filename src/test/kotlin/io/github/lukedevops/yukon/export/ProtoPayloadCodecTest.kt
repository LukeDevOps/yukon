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
    fun `a delta batch's final flush flag round-trips through the wire, both ways`() {
        val finalBatch =
            DeltaBatch(resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "prod"), deltas = emptyList(), finalFlush = true)
        val scheduledBatch = finalBatch.copy(finalFlush = false)

        assertTrue(ProtoDeltaBatch.parseFrom(ProtoPayloadCodec.encode(finalBatch)).finalFlush)
        assertFalse(ProtoDeltaBatch.parseFrom(ProtoPayloadCodec.encode(scheduledBatch)).finalFlush)
        assertEquals(finalBatch, ProtoPayloadCodec.decodeDeltaBatch(ProtoPayloadCodec.encode(finalBatch)))
        assertEquals(scheduledBatch, ProtoPayloadCodec.decodeDeltaBatch(ProtoPayloadCodec.encode(scheduledBatch)))
    }

    @Test
    fun `a delta batch with no final flush field set decodes as false, matching an old payload`() {
        val wireBytes = ProtoDeltaBatch.newBuilder().build().toByteArray()

        val decoded = ProtoPayloadCodec.decodeDeltaBatch(wireBytes)

        assertFalse(decoded.finalFlush)
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
    fun `a probe location's inline flag round-trips through the wire`() {
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
                            inline = true,
                        ),
                        ProbeLocation(
                            classId = 0,
                            probeIndex = 1,
                            kind = ProbeKind.METHOD,
                            className = "com.example.Foo",
                            methodName = "baz",
                            methodDescriptor = "()V",
                            line = 20,
                            branchIndex = null,
                        ),
                    ),
            )

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        assertEquals(manifest, decoded)
        assertTrue(decoded.probes.single { it.methodName == "bar" }.inline)
        assertFalse(decoded.probes.single { it.methodName == "baz" }.inline)
    }

    @Test
    fun `a probe location's generatedBy round-trips through the wire, every value`() {
        fun probeNamed(
            methodName: String,
            generatedBy: GeneratedBy,
        ) = ProbeLocation(
            classId = 0,
            probeIndex = 0,
            kind = ProbeKind.METHOD,
            className = "com.example.Foo",
            methodName = methodName,
            methodDescriptor = "()V",
            line = 10,
            branchIndex = null,
            generatedBy = generatedBy,
        )

        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = "1.0.0",
                probes =
                    listOf(
                        probeNamed("none", GeneratedBy.NONE),
                        probeNamed("values", GeneratedBy.ENUM),
                        probeNamed("copy", GeneratedBy.DATA_CLASS),
                        probeNamed("withBody", GeneratedBy.DEFAULT_IMPLS),
                        probeNamed("toString", GeneratedBy.RECORD),
                    ),
            )

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        assertEquals(manifest, decoded)
        assertEquals(GeneratedBy.NONE, decoded.probes.single { it.methodName == "none" }.generatedBy)
        assertEquals(GeneratedBy.ENUM, decoded.probes.single { it.methodName == "values" }.generatedBy)
        assertEquals(GeneratedBy.DATA_CLASS, decoded.probes.single { it.methodName == "copy" }.generatedBy)
        assertEquals(GeneratedBy.DEFAULT_IMPLS, decoded.probes.single { it.methodName == "withBody" }.generatedBy)
        assertEquals(GeneratedBy.RECORD, decoded.probes.single { it.methodName == "toString" }.generatedBy)
    }

    @Test
    fun `a probe location with no generatedBy set decodes as NONE, matching an old payload`() {
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
                    ),
            )

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        assertEquals(GeneratedBy.NONE, decoded.probes.single().generatedBy)
    }

    @Test
    fun `an optional argument probe's parameter fields round-trip through the wire`() {
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = "1.0.0",
                probes =
                    listOf(
                        ProbeLocation(
                            classId = 0,
                            probeIndex = 0,
                            kind = ProbeKind.OPTIONAL_ARGUMENT,
                            className = "com.example.Foo",
                            methodName = "bar",
                            methodDescriptor = "(I)V",
                            line = 10,
                            branchIndex = null,
                            parameterIndex = 0,
                            parameterName = "count",
                            overridable = true,
                        ),
                        ProbeLocation(
                            classId = 0,
                            probeIndex = 1,
                            kind = ProbeKind.METHOD,
                            className = "com.example.Foo",
                            methodName = "bar",
                            methodDescriptor = "(I)V",
                            line = 10,
                            branchIndex = null,
                        ),
                    ),
            )

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        assertEquals(manifest, decoded)
        val omission = decoded.probes.single { it.kind == ProbeKind.OPTIONAL_ARGUMENT }
        assertEquals(0, omission.parameterIndex)
        assertEquals("count", omission.parameterName)
        assertTrue(omission.overridable)
        val method = decoded.probes.single { it.kind == ProbeKind.METHOD }
        assertEquals(null, method.parameterIndex)
        assertEquals(null, method.parameterName)
        assertFalse(method.overridable)
    }

    @Test
    fun `an optional argument probe with no LocalVariableTable name round-trips as an empty string, not null`() {
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = "1.0.0",
                probes =
                    listOf(
                        ProbeLocation(
                            classId = 0,
                            probeIndex = 0,
                            kind = ProbeKind.OPTIONAL_ARGUMENT,
                            className = "com.example.Foo",
                            methodName = "bar",
                            methodDescriptor = "(I)V",
                            line = 10,
                            branchIndex = null,
                            parameterIndex = 0,
                            parameterName = "",
                        ),
                    ),
            )

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        assertEquals("", decoded.probes.single().parameterName)
    }

    @Test
    fun `a probe location's target class name round-trips through the wire, empty as null`() {
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = "1.0.0",
                probes =
                    listOf(
                        ProbeLocation(
                            classId = 0,
                            probeIndex = 0,
                            kind = ProbeKind.OPTIONAL_ARGUMENT,
                            className = "com.example.scalatarget.Cc\$",
                            methodName = "<init>",
                            methodDescriptor = "(II)V",
                            line = 71,
                            branchIndex = null,
                            parameterIndex = 0,
                            parameterName = "a",
                            overridable = false,
                            targetClassName = "com.example.scalatarget.Cc",
                        ),
                        ProbeLocation(
                            classId = 0,
                            probeIndex = 1,
                            kind = ProbeKind.METHOD,
                            className = "com.example.Foo",
                            methodName = "bar",
                            methodDescriptor = "(I)V",
                            line = 10,
                            branchIndex = null,
                        ),
                    ),
            )

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        assertEquals(manifest, decoded)
        assertEquals("com.example.scalatarget.Cc", decoded.probes[0].targetClassName)
        assertEquals(null, decoded.probes[1].targetClassName)
    }

    @Test
    fun `a probe location's inlined-from class name round-trips through the wire, empty as null`() {
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = "1.0.0",
                probes =
                    listOf(
                        ProbeLocation(
                            classId = 0,
                            probeIndex = 0,
                            kind = ProbeKind.BRANCH,
                            className = "com.example.target.InlinedCopyTargetKt",
                            methodName = "callTakingTrueBranch",
                            methodDescriptor = "(I)I",
                            line = 16,
                            branchIndex = 4,
                            inlinedFromClassName = "com.example.target.InlinedCopyTargetKt",
                        ),
                        ProbeLocation(
                            classId = 0,
                            probeIndex = 1,
                            kind = ProbeKind.BRANCH,
                            className = "com.example.target.InlinedCopyTargetKt",
                            methodName = "useCollections",
                            methodDescriptor = "(Ljava/util/List;)Ljava/lang/Integer;",
                            line = 11,
                            branchIndex = 0,
                        ),
                    ),
            )

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        assertEquals(manifest, decoded)
        assertEquals("com.example.target.InlinedCopyTargetKt", decoded.probes[0].inlinedFromClassName)
        assertEquals(null, decoded.probes[1].inlinedFromClassName)
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
    fun `a declared method's inline flag round-trips through the wire`() {
        val baseline =
            StaticBaseline(
                resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "prod"),
                declaredClasses =
                    listOf(
                        DeclaredClass(
                            className = "com.example.Foo",
                            methods =
                                listOf(
                                    DeclaredMethod(methodName = "bar", methodDescriptor = "()V", inline = true),
                                    DeclaredMethod(methodName = "baz", methodDescriptor = "(I)Z"),
                                ),
                        ),
                    ),
                scannedAt = 1000L,
            )

        val decoded = ProtoPayloadCodec.decodeStaticBaseline(ProtoPayloadCodec.encode(baseline))

        assertEquals(baseline, decoded)
        val methods = decoded.declaredClasses.single().methods
        assertTrue(methods.single { it.methodName == "bar" }.inline)
        assertFalse(methods.single { it.methodName == "baz" }.inline)
    }

    @Test
    fun `a declared method's generatedBy round-trips through the wire, every value`() {
        val baseline =
            StaticBaseline(
                resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "prod"),
                declaredClasses =
                    listOf(
                        DeclaredClass(
                            className = "com.example.Foo",
                            methods =
                                listOf(
                                    DeclaredMethod(methodName = "none", methodDescriptor = "()V", generatedBy = GeneratedBy.NONE),
                                    DeclaredMethod(methodName = "values", methodDescriptor = "()V", generatedBy = GeneratedBy.ENUM),
                                    DeclaredMethod(methodName = "copy", methodDescriptor = "()V", generatedBy = GeneratedBy.DATA_CLASS),
                                    DeclaredMethod(
                                        methodName = "withBody",
                                        methodDescriptor = "()V",
                                        generatedBy = GeneratedBy.DEFAULT_IMPLS,
                                    ),
                                    DeclaredMethod(methodName = "toString", methodDescriptor = "()V", generatedBy = GeneratedBy.RECORD),
                                ),
                        ),
                    ),
                scannedAt = 1000L,
            )

        val decoded = ProtoPayloadCodec.decodeStaticBaseline(ProtoPayloadCodec.encode(baseline))

        assertEquals(baseline, decoded)
        val methods = decoded.declaredClasses.single().methods
        assertEquals(GeneratedBy.NONE, methods.single { it.methodName == "none" }.generatedBy)
        assertEquals(GeneratedBy.ENUM, methods.single { it.methodName == "values" }.generatedBy)
        assertEquals(GeneratedBy.DATA_CLASS, methods.single { it.methodName == "copy" }.generatedBy)
        assertEquals(GeneratedBy.DEFAULT_IMPLS, methods.single { it.methodName == "withBody" }.generatedBy)
        assertEquals(GeneratedBy.RECORD, methods.single { it.methodName == "toString" }.generatedBy)
    }

    @Test
    fun `a declared method's call edges and a declared class's supertypes round-trip through the wire`() {
        val baseline =
            StaticBaseline(
                resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "prod"),
                declaredClasses =
                    listOf(
                        DeclaredClass(
                            className = "com.example.Foo",
                            methods =
                                listOf(
                                    DeclaredMethod(
                                        methodName = "bar",
                                        methodDescriptor = "()V",
                                        calls =
                                            listOf(
                                                CallEdge("com.example.Baz", "qux", "()I", virtual = true),
                                                CallEdge("com.example.Baz", "<init>", "()V", virtual = false),
                                            ),
                                    ),
                                ),
                            superClassName = "com.example.Base",
                            interfaceNames = listOf("com.example.Marker"),
                        ),
                    ),
                scannedAt = 1000L,
            )

        val decoded = ProtoPayloadCodec.decodeStaticBaseline(ProtoPayloadCodec.encode(baseline))

        assertEquals(baseline, decoded)
        assertEquals(
            2,
            decoded.declaredClasses
                .single()
                .methods
                .single()
                .calls.size,
        )
        assertEquals("com.example.Base", decoded.declaredClasses.single().superClassName)
        assertEquals(listOf("com.example.Marker"), decoded.declaredClasses.single().interfaceNames)
    }

    @Test
    fun `a declared class with no superclass round-trips super class name as null, not empty string`() {
        val baseline =
            StaticBaseline(
                resource = ResourceAttributes("checkout", null, "instance-1", null),
                declaredClasses =
                    listOf(DeclaredClass(className = "com.example.Foo", methods = listOf(DeclaredMethod("bar", "()V")))),
                scannedAt = 1000L,
            )

        val decoded = ProtoPayloadCodec.decodeStaticBaseline(ProtoPayloadCodec.encode(baseline))

        assertEquals(baseline, decoded)
        assertEquals(null, decoded.declaredClasses.single().superClassName)
        assertEquals(emptyList(), decoded.declaredClasses.single().interfaceNames)
        assertEquals(
            emptyList(),
            decoded.declaredClasses
                .single()
                .methods
                .single()
                .calls,
        )
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

    @Test
    fun `a METHOD probe's call edges and a class's supertypes round-trip through the wire`() {
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
                            calls =
                                listOf(
                                    CallEdge("com.example.Baz", "qux", "()I", virtual = true),
                                    CallEdge("com.example.Baz", "<init>", "()V", virtual = false),
                                ),
                        ),
                    ),
                classSupertypes =
                    listOf(
                        ClassSupertypes(classId = 0, superClassName = "com.example.Base", interfaceNames = listOf("com.example.Marker")),
                    ),
            )

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        assertEquals(manifest, decoded)
        assertEquals(
            2,
            decoded.probes
                .single()
                .calls.size,
        )
        assertEquals("com.example.Base", decoded.classSupertypes.single().superClassName)
    }

    @Test
    fun `a class supertypes record with no superclass round-trips super class name as null, not empty string`() {
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = null,
                probes = emptyList(),
                classSupertypes = listOf(ClassSupertypes(classId = 0, superClassName = null, interfaceNames = emptyList())),
            )

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        assertEquals(manifest, decoded)
        assertEquals(null, decoded.classSupertypes.single().superClassName)
    }

    @Test
    fun `a manifest with no calls or supertypes decodes to empty lists, matching an old payload`() {
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = null,
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
                    ),
            )

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        assertTrue(
            decoded.probes
                .single()
                .calls
                .isEmpty(),
        )
        assertTrue(decoded.classSupertypes.isEmpty())
        assertEquals(manifest, decoded)
    }

    @Test
    fun `a manifest's unreported classes survive a round trip`() {
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = null,
                probes = emptyList(),
                serviceInstanceId = "instance-1",
                unreportedClasses = listOf(UnreportedClass("com.example.Deflected", 1_700_000_000_000L)),
            )

        val decoded = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))

        val unreported = decoded.unreportedClasses.single()
        assertEquals("com.example.Deflected", unreported.className)
        assertEquals(1_700_000_000_000L, unreported.firstSeenUnreportedAt)
    }
}
