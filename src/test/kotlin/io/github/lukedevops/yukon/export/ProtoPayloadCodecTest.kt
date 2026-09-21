package io.github.lukedevops.yukon.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.github.lukedevops.yukon.proto.DeltaBatch as ProtoDeltaBatch
import io.github.lukedevops.yukon.proto.DependencyDiscoverySource as ProtoDependencyDiscoverySource
import io.github.lukedevops.yukon.proto.DependencyIdentity as ProtoDependencyIdentity
import io.github.lukedevops.yukon.proto.DependencyIdentitySource as ProtoDependencyIdentitySource
import io.github.lukedevops.yukon.proto.DependencyLocation as ProtoDependencyLocation
import io.github.lukedevops.yukon.proto.EndpointDiscoverySource as ProtoEndpointDiscoverySource
import io.github.lukedevops.yukon.proto.EndpointLocation as ProtoEndpointLocation
import io.github.lukedevops.yukon.proto.ExternalClass as ProtoExternalClass
import io.github.lukedevops.yukon.proto.ProbeDelta as ProtoProbeDelta
import io.github.lukedevops.yukon.proto.ProbeKind as ProtoProbeKind
import io.github.lukedevops.yukon.proto.ProbeLocation as ProtoProbeLocation
import io.github.lukedevops.yukon.proto.ProbeManifest as ProtoProbeManifest
import io.github.lukedevops.yukon.proto.StaticBaseline as ProtoStaticBaseline

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

    @Test
    fun `an enum number this codec does not know is an error on decode, never silently the default`() {
        val batch =
            ProtoDeltaBatch
                .newBuilder()
                .addDeltas(
                    ProtoProbeDelta
                        .newBuilder()
                        .setClassId(1)
                        .setProbeIndex(0)
                        .setKindValue(99)
                        .setHitsTotal(1),
                ).build()
                .toByteArray()
        assertFailsWith<IllegalArgumentException> { ProtoPayloadCodec.decodeDeltaBatch(batch) }

        val generatedBy =
            ProtoProbeManifest
                .newBuilder()
                .addProbes(
                    ProtoProbeLocation
                        .newBuilder()
                        .setClassId(1)
                        .setKind(ProtoProbeKind.METHOD)
                        .setGeneratedByValue(99),
                ).build()
                .toByteArray()
        assertFailsWith<IllegalArgumentException> { ProtoPayloadCodec.decodeProbeManifest(generatedBy) }

        val discoverySource =
            ProtoProbeManifest
                .newBuilder()
                .addEndpoints(ProtoEndpointLocation.newBuilder().setEndpointId(1).setDiscoverySourceValue(99))
                .build()
                .toByteArray()
        assertFailsWith<IllegalArgumentException> { ProtoPayloadCodec.decodeProbeManifest(discoverySource) }
    }

    @Test
    fun `a delta batch's dependency deltas round-trip through the wire, both ways`() {
        val batch =
            DeltaBatch(
                resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "prod"),
                deltas = emptyList(),
                dependencyDeltas =
                    listOf(
                        DependencyDelta(dependencyId = 0, firstLoadedAt = 1000L, loadedClassesTotal = 12L),
                        DependencyDelta(dependencyId = 3, firstLoadedAt = 2000L, loadedClassesTotal = 1L),
                    ),
            )

        val bytes = ProtoPayloadCodec.encode(batch)
        val wire = ProtoDeltaBatch.parseFrom(bytes)

        assertEquals(2, wire.dependencyDeltasList.size)
        assertEquals(0, wire.dependencyDeltasList[0].dependencyId)
        assertEquals(1000L, wire.dependencyDeltasList[0].firstLoadedAt)
        assertEquals(12L, wire.dependencyDeltasList[0].loadedClassesTotal)
        assertEquals(3, wire.dependencyDeltasList[1].dependencyId)
        assertEquals(batch, ProtoPayloadCodec.decodeDeltaBatch(bytes))
    }

    @Test
    fun `a delta batch with no dependency deltas decodes to an empty list, matching an old payload`() {
        val batch =
            DeltaBatch(
                resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "prod"),
                deltas = emptyList(),
            )

        val decoded = ProtoPayloadCodec.decodeDeltaBatch(ProtoPayloadCodec.encode(batch))

        assertTrue(decoded.dependencyDeltas.isEmpty())
        assertEquals(batch, decoded)
    }

    @Test
    fun `a manifest's dependencies round-trip through the wire, with empty group and version as null`() {
        val ordinary =
            DependencyLocation(
                dependencyId = 0,
                identities = listOf(DependencyIdentity("com.squareup.okhttp3", "okhttp", "4.12.0")),
                identitySource = DependencyIdentitySource.POM_PROPERTIES,
                location = "/app/lib/okhttp-4.12.0.jar",
                discoverySource = DependencyDiscoverySource.STARTUP_CLASSPATH,
                classCount = 420,
            )
        val shaded =
            DependencyLocation(
                dependencyId = 1,
                identities =
                    listOf(
                        DependencyIdentity("com.example", "bundle", "2.0"),
                        DependencyIdentity("com.google.guava", "guava", "33.0.0-jre"),
                    ),
                identitySource = DependencyIdentitySource.POM_PROPERTIES,
                location = "BOOT-INF/lib/bundle-2.0.jar",
                discoverySource = DependencyDiscoverySource.STARTUP_CLASSPATH,
                classCount = 9000,
            )
        val filenameOnly =
            DependencyLocation(
                dependencyId = 2,
                identities = listOf(DependencyIdentity(groupId = null, artifactId = "legacy-utils", version = null)),
                identitySource = DependencyIdentitySource.FILENAME,
                location = "WEB-INF/lib/legacy-utils.jar",
                discoverySource = DependencyDiscoverySource.LOAD,
            )
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = "1.0.0",
                probes = emptyList(),
                serviceInstanceId = "instance-1",
                dependencies = listOf(ordinary, shaded, filenameOnly),
            )

        val bytes = ProtoPayloadCodec.encode(manifest)
        val wire = ProtoProbeManifest.parseFrom(bytes)

        assertEquals(3, wire.dependenciesList.size)
        assertEquals(ProtoDependencyIdentitySource.POM_PROPERTIES, wire.dependenciesList[0].identitySource)
        assertEquals(ProtoDependencyDiscoverySource.STARTUP_CLASSPATH, wire.dependenciesList[0].discoverySource)
        assertTrue(wire.dependenciesList[0].hasClassCount())
        assertEquals(420, wire.dependenciesList[0].classCount)
        assertEquals(0, wire.dependenciesList[0].dependencyId)
        assertEquals("/app/lib/okhttp-4.12.0.jar", wire.dependenciesList[0].location)
        val wireOrdinaryIdentity = wire.dependenciesList[0].identitiesList.single()
        assertEquals("com.squareup.okhttp3", wireOrdinaryIdentity.groupId)
        assertEquals("okhttp", wireOrdinaryIdentity.artifactId)
        assertEquals("4.12.0", wireOrdinaryIdentity.version)
        assertEquals(2, wire.dependenciesList[1].identitiesCount)
        val wireFilenameOnly = wire.dependenciesList[2]
        assertEquals(ProtoDependencyIdentitySource.FILENAME, wireFilenameOnly.identitySource)
        assertEquals(ProtoDependencyDiscoverySource.LOAD, wireFilenameOnly.discoverySource)
        assertFalse(wireFilenameOnly.hasClassCount())
        assertEquals("", wireFilenameOnly.identitiesList.single().groupId)
        assertEquals("legacy-utils", wireFilenameOnly.identitiesList.single().artifactId)
        assertEquals("", wireFilenameOnly.identitiesList.single().version)
        assertEquals(manifest, ProtoPayloadCodec.decodeProbeManifest(bytes))
    }

    @Test
    fun `a class count of zero is present on the wire and decodes as zero, not null`() {
        val manifest =
            ProbeManifest(
                serviceName = "checkout",
                serviceVersion = "1.0.0",
                probes = emptyList(),
                serviceInstanceId = "instance-1",
                dependencies =
                    listOf(
                        DependencyLocation(
                            dependencyId = 0,
                            identities = listOf(DependencyIdentity("org.webjars", "bootstrap", "5.3.3")),
                            identitySource = DependencyIdentitySource.POM_PROPERTIES,
                            location = "/app/lib/bootstrap-5.3.3.jar",
                            discoverySource = DependencyDiscoverySource.STARTUP_CLASSPATH,
                            classCount = 0,
                        ),
                    ),
            )

        val bytes = ProtoPayloadCodec.encode(manifest)

        assertTrue(
            ProtoProbeManifest
                .parseFrom(bytes)
                .dependenciesList
                .single()
                .hasClassCount(),
        )
        assertEquals(
            0,
            ProtoPayloadCodec
                .decodeProbeManifest(bytes)
                .dependencies
                .single()
                .classCount,
        )
    }

    @Test
    fun `a manifest's class references, external classes and a METHOD probe's referenced classes round-trip`() {
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
                            className = "com.example.Checkout",
                            methodName = "pay",
                            methodDescriptor = "()V",
                            line = 10,
                            branchIndex = null,
                            referencedClasses = listOf("okhttp3.OkHttpClient", "com.optional.Missing"),
                        ),
                    ),
                serviceInstanceId = "instance-1",
                classReferences =
                    listOf(ClassReferences(classId = 0, referencedClasses = listOf("org.springframework.stereotype.Service"))),
                externalClasses =
                    listOf(
                        ExternalClass("okhttp3.OkHttpClient", dependencyId = 0),
                        ExternalClass("org.springframework.stereotype.Service", dependencyId = 1),
                        ExternalClass("com.optional.Missing", dependencyId = null, absent = true),
                    ),
            )

        val bytes = ProtoPayloadCodec.encode(manifest)
        val wire = ProtoProbeManifest.parseFrom(bytes)

        assertEquals(listOf("okhttp3.OkHttpClient", "com.optional.Missing"), wire.probesList.single().referencedClassesList)
        assertEquals(0, wire.classReferencesList.single().classId)
        assertEquals(listOf("org.springframework.stereotype.Service"), wire.classReferencesList.single().referencedClassesList)
        assertEquals(3, wire.externalClassesList.size)
        assertEquals("okhttp3.OkHttpClient", wire.externalClassesList[0].className)
        assertTrue(wire.externalClassesList[0].hasDependencyId())
        assertEquals(0, wire.externalClassesList[0].dependencyId)
        assertEquals(1, wire.externalClassesList[1].dependencyId)
        assertFalse(wire.externalClassesList[0].absent)
        assertFalse(wire.externalClassesList[2].hasDependencyId())
        assertTrue(wire.externalClassesList[2].absent)
        assertEquals(manifest, ProtoPayloadCodec.decodeProbeManifest(bytes))
    }

    @Test
    fun `a declared method's and class's referenced classes and a baseline's external classes round-trip`() {
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
                                        referencedClasses = listOf("okhttp3.Request"),
                                    ),
                                ),
                            superClassName = "java.lang.Object",
                            referencedClasses = listOf("jakarta.inject.Singleton"),
                        ),
                    ),
                scannedAt = 1000L,
                externalClasses =
                    listOf(
                        ExternalClass("okhttp3.Request", dependencyId = 0),
                        ExternalClass("jakarta.inject.Singleton", dependencyId = null, absent = true),
                    ),
            )

        val bytes = ProtoPayloadCodec.encode(baseline)
        val wire = ProtoStaticBaseline.parseFrom(bytes)
        val decoded = ProtoPayloadCodec.decodeStaticBaseline(bytes)

        val wireClass = wire.declaredClassesList.single()
        assertEquals(listOf("jakarta.inject.Singleton"), wireClass.referencedClassesList)
        assertEquals(listOf("okhttp3.Request"), wireClass.methodsList.single().referencedClassesList)
        assertEquals(listOf("okhttp3.Request", "jakarta.inject.Singleton"), wire.externalClassesList.map { it.className })
        assertEquals(0, wire.externalClassesList[0].dependencyId)
        assertTrue(wire.externalClassesList[1].absent)
        assertEquals(baseline, decoded)
        assertEquals(
            listOf("okhttp3.Request"),
            decoded.declaredClasses
                .single()
                .methods
                .single()
                .referencedClasses,
        )
        assertEquals(listOf("jakarta.inject.Singleton"), decoded.declaredClasses.single().referencedClasses)
        assertEquals(2, decoded.externalClasses.size)
    }

    @Test
    fun `a manifest and a baseline with no dependency fields decode to empty lists, matching an old payload`() {
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
                            className = "com.example.Checkout",
                            methodName = "pay",
                            methodDescriptor = "()V",
                            line = 10,
                            branchIndex = null,
                        ),
                    ),
            )
        val baseline =
            StaticBaseline(
                resource = ResourceAttributes("checkout", null, "instance-1", null),
                declaredClasses =
                    listOf(DeclaredClass("com.example.Foo", listOf(DeclaredMethod("bar", "()V")), superClassName = "java.lang.Object")),
                scannedAt = 1000L,
            )

        val decodedManifest = ProtoPayloadCodec.decodeProbeManifest(ProtoPayloadCodec.encode(manifest))
        val decodedBaseline = ProtoPayloadCodec.decodeStaticBaseline(ProtoPayloadCodec.encode(baseline))

        assertTrue(decodedManifest.dependencies.isEmpty())
        assertTrue(decodedManifest.classReferences.isEmpty())
        assertTrue(decodedManifest.externalClasses.isEmpty())
        assertTrue(
            decodedManifest.probes
                .single()
                .referencedClasses
                .isEmpty(),
        )
        assertEquals(manifest, decodedManifest)
        assertTrue(decodedBaseline.externalClasses.isEmpty())
        assertTrue(
            decodedBaseline.declaredClasses
                .single()
                .referencedClasses
                .isEmpty(),
        )
        assertTrue(
            decodedBaseline.declaredClasses
                .single()
                .methods
                .single()
                .referencedClasses
                .isEmpty(),
        )
        assertEquals(baseline, decodedBaseline)
    }

    @Test
    fun `a dependency with an unspecified or unknown identity or discovery source fails to decode`() {
        fun manifestWith(dependency: ProtoDependencyLocation.Builder): ByteArray =
            ProtoProbeManifest
                .newBuilder()
                .addDependencies(dependency)
                .build()
                .toByteArray()

        fun validDependency(): ProtoDependencyLocation.Builder =
            ProtoDependencyLocation
                .newBuilder()
                .setDependencyId(0)
                .addIdentities(ProtoDependencyIdentity.newBuilder().setArtifactId("okhttp"))
                .setIdentitySource(ProtoDependencyIdentitySource.POM_PROPERTIES)
                .setLocation("okhttp.jar")
                .setDiscoverySource(ProtoDependencyDiscoverySource.STARTUP_CLASSPATH)

        ProtoPayloadCodec.decodeProbeManifest(manifestWith(validDependency()))

        val invalid =
            listOf(
                validDependency().setIdentitySource(ProtoDependencyIdentitySource.DEPENDENCY_IDENTITY_SOURCE_UNSPECIFIED),
                validDependency().setIdentitySourceValue(99),
                validDependency().setDiscoverySource(ProtoDependencyDiscoverySource.DEPENDENCY_DISCOVERY_SOURCE_UNSPECIFIED),
                validDependency().setDiscoverySourceValue(99),
            )
        for (dependency in invalid) {
            assertFailsWith<IllegalArgumentException> { ProtoPayloadCodec.decodeProbeManifest(manifestWith(dependency)) }
        }
    }

    @Test
    fun `a dependency with no identities fails to decode`() {
        val manifest =
            ProtoProbeManifest
                .newBuilder()
                .addDependencies(
                    ProtoDependencyLocation
                        .newBuilder()
                        .setDependencyId(0)
                        .setIdentitySource(ProtoDependencyIdentitySource.FILENAME)
                        .setLocation("mystery.jar")
                        .setDiscoverySource(ProtoDependencyDiscoverySource.LOAD),
                ).build()
                .toByteArray()

        assertFailsWith<IllegalArgumentException> { ProtoPayloadCodec.decodeProbeManifest(manifest) }
    }

    @Test
    fun `an external class that is both mapped and absent, or neither, fails to decode and names the class`() {
        val both =
            ProtoExternalClass
                .newBuilder()
                .setClassName("com.example.Both")
                .setDependencyId(0)
                .setAbsent(true)
        val neither = ProtoExternalClass.newBuilder().setClassName("com.example.Neither")

        for (externalClass in listOf(both, neither)) {
            val manifest =
                ProtoProbeManifest
                    .newBuilder()
                    .addExternalClasses(externalClass)
                    .build()
                    .toByteArray()
            val baseline =
                ProtoStaticBaseline
                    .newBuilder()
                    .addExternalClasses(externalClass)
                    .build()
                    .toByteArray()

            val manifestError = assertFailsWith<IllegalArgumentException> { ProtoPayloadCodec.decodeProbeManifest(manifest) }
            val baselineError = assertFailsWith<IllegalArgumentException> { ProtoPayloadCodec.decodeStaticBaseline(baseline) }
            assertTrue(manifestError.message!!.contains(externalClass.className), manifestError.message)
            assertTrue(baselineError.message!!.contains(externalClass.className), baselineError.message)
        }
    }

    @Test
    fun `an external class must be exactly one of mapped to a dependency or absent`() {
        assertFailsWith<IllegalArgumentException> { ExternalClass("x", 1, absent = true) }
        assertFailsWith<IllegalArgumentException> { ExternalClass("x", null) }
    }

    @Test
    fun `a dependency location must carry at least one identity`() {
        assertFailsWith<IllegalArgumentException> {
            DependencyLocation(
                dependencyId = 0,
                identities = emptyList(),
                identitySource = DependencyIdentitySource.FILENAME,
                location = "mystery.jar",
                discoverySource = DependencyDiscoverySource.LOAD,
            )
        }
    }
}
