package io.github.lukedevops.yukon.export

import io.github.lukedevops.yukon.proto.DeclaredClass as ProtoDeclaredClass
import io.github.lukedevops.yukon.proto.DeclaredMethod as ProtoDeclaredMethod
import io.github.lukedevops.yukon.proto.DeltaBatch as ProtoDeltaBatch
import io.github.lukedevops.yukon.proto.DisabledEndpointModule as ProtoDisabledEndpointModule
import io.github.lukedevops.yukon.proto.EndpointDelta as ProtoEndpointDelta
import io.github.lukedevops.yukon.proto.EndpointDiscoverySource as ProtoEndpointDiscoverySource
import io.github.lukedevops.yukon.proto.EndpointLocation as ProtoEndpointLocation
import io.github.lukedevops.yukon.proto.ProbeDelta as ProtoProbeDelta
import io.github.lukedevops.yukon.proto.ProbeKind as ProtoProbeKind
import io.github.lukedevops.yukon.proto.ProbeLocation as ProtoProbeLocation
import io.github.lukedevops.yukon.proto.ProbeManifest as ProtoProbeManifest
import io.github.lukedevops.yukon.proto.ResourceAttributes as ProtoResourceAttributes
import io.github.lukedevops.yukon.proto.SkippedClass as ProtoSkippedClass
import io.github.lukedevops.yukon.proto.StaticBaseline as ProtoStaticBaseline
import io.github.lukedevops.yukon.proto.StaticallyUnsafeClass as ProtoStaticallyUnsafeClass
import io.github.lukedevops.yukon.proto.UnprobedClass as ProtoUnprobedClass
import io.github.lukedevops.yukon.proto.UnreadableClass as ProtoUnreadableClass

/**
 * Encodes and decodes [DeltaBatch], [ProbeManifest], and [StaticBaseline] to and from the wire
 * schema defined in `yukon.proto`. This lets an [Exporter] implementation, or anything reading
 * what one sent, work without depending on the generated protobuf classes directly.
 */
object ProtoPayloadCodec {
    fun encode(batch: DeltaBatch): ByteArray = toProto(batch).toByteArray()

    fun encode(manifest: ProbeManifest): ByteArray = toProto(manifest).toByteArray()

    fun encode(baseline: StaticBaseline): ByteArray = toProto(baseline).toByteArray()

    fun decodeDeltaBatch(bytes: ByteArray): DeltaBatch = fromProto(ProtoDeltaBatch.parseFrom(bytes))

    fun decodeProbeManifest(bytes: ByteArray): ProbeManifest = fromProto(ProtoProbeManifest.parseFrom(bytes))

    fun decodeStaticBaseline(bytes: ByteArray): StaticBaseline = fromProto(ProtoStaticBaseline.parseFrom(bytes))

    private fun toProto(batch: DeltaBatch): ProtoDeltaBatch =
        ProtoDeltaBatch
            .newBuilder()
            .setResource(toProto(batch.resource))
            .addAllDeltas(batch.deltas.map { toProto(it) })
            .addAllEndpointDeltas(batch.endpointDeltas.map { toProto(it) })
            .build()

    private fun fromProto(batch: ProtoDeltaBatch): DeltaBatch =
        DeltaBatch(
            resource = fromProto(batch.resource),
            deltas = batch.deltasList.map { fromProto(it) },
            endpointDeltas = batch.endpointDeltasList.map { fromProto(it) },
        )

    private fun toProto(resource: ResourceAttributes): ProtoResourceAttributes {
        val builder =
            ProtoResourceAttributes
                .newBuilder()
                .setServiceName(resource.serviceName)
                .setServiceInstanceId(resource.serviceInstanceId)
        resource.serviceVersion?.let { builder.serviceVersion = it }
        resource.environment?.let { builder.environment = it }
        return builder.build()
    }

    private fun fromProto(resource: ProtoResourceAttributes): ResourceAttributes =
        ResourceAttributes(
            serviceName = resource.serviceName,
            serviceVersion = if (resource.hasServiceVersion()) resource.serviceVersion else null,
            serviceInstanceId = resource.serviceInstanceId,
            environment = if (resource.hasEnvironment()) resource.environment else null,
        )

    private fun toProto(delta: ProbeDelta): ProtoProbeDelta =
        ProtoProbeDelta
            .newBuilder()
            .setClassId(delta.classId)
            .setProbeIndex(delta.probeIndex)
            .setKind(toProto(delta.kind))
            .setFirstSeenAt(delta.firstSeenAt)
            .setHitsTotal(delta.hitsTotal)
            .build()

    private fun fromProto(delta: ProtoProbeDelta): ProbeDelta =
        ProbeDelta(
            classId = delta.classId,
            probeIndex = delta.probeIndex,
            kind = fromProto(delta.kind),
            firstSeenAt = delta.firstSeenAt,
            hitsTotal = delta.hitsTotal,
        )

    private fun toProto(manifest: ProbeManifest): ProtoProbeManifest {
        val builder =
            ProtoProbeManifest
                .newBuilder()
                .setServiceName(manifest.serviceName)
                .addAllProbes(manifest.probes.map { toProto(it) })
                .addAllSkippedClasses(manifest.skippedClasses.map { toProto(it) })
                .setServiceInstanceId(manifest.serviceInstanceId)
                .addAllEndpoints(manifest.endpoints.map { toProto(it) })
                .addAllDisabledEndpointModules(manifest.disabledEndpointModules.map { toProto(it) })
        manifest.serviceVersion?.let { builder.serviceVersion = it }
        return builder.build()
    }

    private fun fromProto(manifest: ProtoProbeManifest): ProbeManifest =
        ProbeManifest(
            serviceName = manifest.serviceName,
            serviceVersion = if (manifest.hasServiceVersion()) manifest.serviceVersion else null,
            probes = manifest.probesList.map { fromProto(it) },
            skippedClasses = manifest.skippedClassesList.map { fromProto(it) },
            serviceInstanceId = manifest.serviceInstanceId,
            endpoints = manifest.endpointsList.map { fromProto(it) },
            disabledEndpointModules = manifest.disabledEndpointModulesList.map { fromProto(it) },
        )

    private fun toProto(skippedClass: SkippedClass): ProtoSkippedClass =
        ProtoSkippedClass
            .newBuilder()
            .setClassName(skippedClass.className)
            .setReason(skippedClass.reason)
            .setSkippedAt(skippedClass.skippedAt)
            .build()

    private fun fromProto(skippedClass: ProtoSkippedClass): SkippedClass =
        SkippedClass(
            className = skippedClass.className,
            reason = skippedClass.reason,
            skippedAt = skippedClass.skippedAt,
        )

    private fun toProto(location: ProbeLocation): ProtoProbeLocation {
        val builder =
            ProtoProbeLocation
                .newBuilder()
                .setClassId(location.classId)
                .setProbeIndex(location.probeIndex)
                .setKind(toProto(location.kind))
                .setClassName(location.className)
                .setMethodName(location.methodName)
                .setMethodDescriptor(location.methodDescriptor)
                .setLine(location.line)
        location.branchIndex?.let { builder.branchIndex = it }
        return builder.build()
    }

    private fun fromProto(location: ProtoProbeLocation): ProbeLocation =
        ProbeLocation(
            classId = location.classId,
            probeIndex = location.probeIndex,
            kind = fromProto(location.kind),
            className = location.className,
            methodName = location.methodName,
            methodDescriptor = location.methodDescriptor,
            line = location.line,
            branchIndex = if (location.hasBranchIndex()) location.branchIndex else null,
        )

    private fun toProto(kind: ProbeKind): ProtoProbeKind =
        when (kind) {
            ProbeKind.METHOD -> ProtoProbeKind.METHOD
            ProbeKind.BRANCH -> ProtoProbeKind.BRANCH
        }

    private fun fromProto(kind: ProtoProbeKind): ProbeKind =
        when (kind) {
            ProtoProbeKind.METHOD -> {
                ProbeKind.METHOD
            }

            ProtoProbeKind.BRANCH -> {
                ProbeKind.BRANCH
            }

            ProtoProbeKind.PROBE_KIND_UNSPECIFIED, ProtoProbeKind.UNRECOGNIZED -> {
                throw IllegalArgumentException("unrecognized probe kind on the wire: $kind")
            }
        }

    private fun toProto(baseline: StaticBaseline): ProtoStaticBaseline =
        ProtoStaticBaseline
            .newBuilder()
            .setResource(toProto(baseline.resource))
            .addAllDeclaredClasses(baseline.declaredClasses.map { toProto(it) })
            .addAllStaticallyUnsafeClasses(baseline.staticallyUnsafeClasses.map { toProto(it) })
            .addAllUnreadableClasses(baseline.unreadableClasses.map { toProto(it) })
            .addAllUnprobedClasses(baseline.unprobedClasses.map { toProto(it) })
            .setScannedAt(baseline.scannedAt)
            .setChunkIndex(baseline.chunkIndex)
            .setChunkCount(baseline.chunkCount)
            .build()

    private fun fromProto(baseline: ProtoStaticBaseline): StaticBaseline =
        StaticBaseline(
            resource = fromProto(baseline.resource),
            declaredClasses = baseline.declaredClassesList.map { fromProto(it) },
            staticallyUnsafeClasses = baseline.staticallyUnsafeClassesList.map { fromProto(it) },
            unreadableClasses = baseline.unreadableClassesList.map { fromProto(it) },
            unprobedClasses = baseline.unprobedClassesList.map { fromProto(it) },
            scannedAt = baseline.scannedAt,
            chunkIndex = baseline.chunkIndex,
            chunkCount = baseline.chunkCount,
        )

    private fun toProto(unprobedClass: UnprobedClass): ProtoUnprobedClass =
        ProtoUnprobedClass
            .newBuilder()
            .setClassName(unprobedClass.className)
            .setReason(unprobedClass.reason)
            .build()

    private fun fromProto(unprobedClass: ProtoUnprobedClass): UnprobedClass =
        UnprobedClass(
            className = unprobedClass.className,
            reason = unprobedClass.reason,
        )

    private fun toProto(declaredClass: DeclaredClass): ProtoDeclaredClass =
        ProtoDeclaredClass
            .newBuilder()
            .setClassName(declaredClass.className)
            .addAllMethods(declaredClass.methods.map { toProto(it) })
            .build()

    private fun fromProto(declaredClass: ProtoDeclaredClass): DeclaredClass =
        DeclaredClass(
            className = declaredClass.className,
            methods = declaredClass.methodsList.map { fromProto(it) },
        )

    private fun toProto(method: DeclaredMethod): ProtoDeclaredMethod =
        ProtoDeclaredMethod
            .newBuilder()
            .setMethodName(method.methodName)
            .setMethodDescriptor(method.methodDescriptor)
            .build()

    private fun fromProto(method: ProtoDeclaredMethod): DeclaredMethod =
        DeclaredMethod(
            methodName = method.methodName,
            methodDescriptor = method.methodDescriptor,
        )

    private fun toProto(unsafeClass: StaticallyUnsafeClass): ProtoStaticallyUnsafeClass =
        ProtoStaticallyUnsafeClass
            .newBuilder()
            .setClassName(unsafeClass.className)
            .setReason(unsafeClass.reason)
            .build()

    private fun fromProto(unsafeClass: ProtoStaticallyUnsafeClass): StaticallyUnsafeClass =
        StaticallyUnsafeClass(
            className = unsafeClass.className,
            reason = unsafeClass.reason,
        )

    private fun toProto(unreadableClass: UnreadableClass): ProtoUnreadableClass =
        ProtoUnreadableClass
            .newBuilder()
            .setClassName(unreadableClass.className)
            .setReason(unreadableClass.reason)
            .build()

    private fun fromProto(unreadableClass: ProtoUnreadableClass): UnreadableClass =
        UnreadableClass(
            className = unreadableClass.className,
            reason = unreadableClass.reason,
        )

    private fun toProto(location: EndpointLocation): ProtoEndpointLocation {
        val builder =
            ProtoEndpointLocation
                .newBuilder()
                .setEndpointId(location.endpointId)
                .setVerb(location.verb)
                .setRouteTemplate(location.routeTemplate)
                .setVerbatimTemplate(location.verbatimTemplate)
                .setFramework(location.framework)
                .setDiscoverySource(toProto(location.discoverySource))
        location.handlerClass?.let { builder.handlerClass = it }
        location.handlerMethod?.let { builder.handlerMethod = it }
        location.handlerDescriptor?.let { builder.handlerDescriptor = it }
        return builder.build()
    }

    private fun fromProto(location: ProtoEndpointLocation): EndpointLocation =
        EndpointLocation(
            endpointId = location.endpointId,
            verb = location.verb,
            routeTemplate = location.routeTemplate,
            verbatimTemplate = location.verbatimTemplate,
            framework = location.framework,
            discoverySource = fromProto(location.discoverySource),
            handlerClass = if (location.hasHandlerClass()) location.handlerClass else null,
            handlerMethod = if (location.hasHandlerMethod()) location.handlerMethod else null,
            handlerDescriptor = if (location.hasHandlerDescriptor()) location.handlerDescriptor else null,
        )

    private fun toProto(source: EndpointDiscoverySource): ProtoEndpointDiscoverySource =
        when (source) {
            EndpointDiscoverySource.REGISTRATION -> ProtoEndpointDiscoverySource.REGISTRATION
            EndpointDiscoverySource.DISPATCH -> ProtoEndpointDiscoverySource.DISPATCH
        }

    private fun fromProto(source: ProtoEndpointDiscoverySource): EndpointDiscoverySource =
        when (source) {
            ProtoEndpointDiscoverySource.REGISTRATION -> {
                EndpointDiscoverySource.REGISTRATION
            }

            ProtoEndpointDiscoverySource.DISPATCH -> {
                EndpointDiscoverySource.DISPATCH
            }

            ProtoEndpointDiscoverySource.ENDPOINT_DISCOVERY_SOURCE_UNSPECIFIED, ProtoEndpointDiscoverySource.UNRECOGNIZED -> {
                throw IllegalArgumentException("unrecognized endpoint discovery source on the wire: $source")
            }
        }

    private fun toProto(delta: EndpointDelta): ProtoEndpointDelta =
        ProtoEndpointDelta
            .newBuilder()
            .setEndpointId(delta.endpointId)
            .setFirstSeenAt(delta.firstSeenAt)
            .setHitsTotal(delta.hitsTotal)
            .build()

    private fun fromProto(delta: ProtoEndpointDelta): EndpointDelta =
        EndpointDelta(
            endpointId = delta.endpointId,
            firstSeenAt = delta.firstSeenAt,
            hitsTotal = delta.hitsTotal,
        )

    private fun toProto(module: DisabledEndpointModule): ProtoDisabledEndpointModule =
        ProtoDisabledEndpointModule
            .newBuilder()
            .setModule(module.module)
            .setReason(module.reason)
            .setDisabledAt(module.disabledAt)
            .build()

    private fun fromProto(module: ProtoDisabledEndpointModule): DisabledEndpointModule =
        DisabledEndpointModule(
            module = module.module,
            reason = module.reason,
            disabledAt = module.disabledAt,
        )
}
