package io.github.lukedevops.yukon.export

import io.github.lukedevops.yukon.proto.DeltaBatch as ProtoDeltaBatch
import io.github.lukedevops.yukon.proto.ProbeDelta as ProtoProbeDelta
import io.github.lukedevops.yukon.proto.ProbeKind as ProtoProbeKind
import io.github.lukedevops.yukon.proto.ProbeLocation as ProtoProbeLocation
import io.github.lukedevops.yukon.proto.ProbeManifest as ProtoProbeManifest
import io.github.lukedevops.yukon.proto.ResourceAttributes as ProtoResourceAttributes
import io.github.lukedevops.yukon.proto.SkippedClass as ProtoSkippedClass

/**
 * Encodes [DeltaBatch] and [ProbeManifest] to the wire schema defined in
 * `yukon.proto`. This lets an [Exporter] implementation send them without
 * depending on the generated protobuf classes directly.
 */
object ProtoPayloadCodec {
    fun encode(batch: DeltaBatch): ByteArray = toProto(batch).toByteArray()

    fun encode(manifest: ProbeManifest): ByteArray = toProto(manifest).toByteArray()

    private fun toProto(batch: DeltaBatch): ProtoDeltaBatch =
        ProtoDeltaBatch
            .newBuilder()
            .setResource(toProto(batch.resource))
            .addAllDeltas(batch.deltas.map { toProto(it) })
            .build()

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

    private fun toProto(delta: ProbeDelta): ProtoProbeDelta =
        ProtoProbeDelta
            .newBuilder()
            .setClassId(delta.classId)
            .setProbeIndex(delta.probeIndex)
            .setKind(toProto(delta.kind))
            .setFirstSeenAt(delta.firstSeenAt)
            .setHitsSinceLastFlush(delta.hitsSinceLastFlush)
            .build()

    private fun toProto(manifest: ProbeManifest): ProtoProbeManifest {
        val builder =
            ProtoProbeManifest
                .newBuilder()
                .setServiceName(manifest.serviceName)
                .addAllProbes(manifest.probes.map { toProto(it) })
                .addAllSkippedClasses(manifest.skippedClasses.map { toProto(it) })
        manifest.serviceVersion?.let { builder.serviceVersion = it }
        return builder.build()
    }

    private fun toProto(skippedClass: SkippedClass): ProtoSkippedClass =
        ProtoSkippedClass
            .newBuilder()
            .setClassName(skippedClass.className)
            .setReason(skippedClass.reason)
            .setSkippedAt(skippedClass.skippedAt)
            .build()

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

    private fun toProto(kind: ProbeKind): ProtoProbeKind =
        when (kind) {
            ProbeKind.METHOD -> ProtoProbeKind.METHOD
            ProbeKind.BRANCH -> ProtoProbeKind.BRANCH
        }
}
