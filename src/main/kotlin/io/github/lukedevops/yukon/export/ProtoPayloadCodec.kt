package io.github.lukedevops.yukon.export

import io.github.lukedevops.yukon.proto.CallEdge as ProtoCallEdge
import io.github.lukedevops.yukon.proto.ClassReferences as ProtoClassReferences
import io.github.lukedevops.yukon.proto.ClassSupertypes as ProtoClassSupertypes
import io.github.lukedevops.yukon.proto.DeclaredClass as ProtoDeclaredClass
import io.github.lukedevops.yukon.proto.DeclaredMethod as ProtoDeclaredMethod
import io.github.lukedevops.yukon.proto.DeltaBatch as ProtoDeltaBatch
import io.github.lukedevops.yukon.proto.DependencyDelta as ProtoDependencyDelta
import io.github.lukedevops.yukon.proto.DependencyDiscoverySource as ProtoDependencyDiscoverySource
import io.github.lukedevops.yukon.proto.DependencyIdentity as ProtoDependencyIdentity
import io.github.lukedevops.yukon.proto.DependencyIdentitySource as ProtoDependencyIdentitySource
import io.github.lukedevops.yukon.proto.DependencyLocation as ProtoDependencyLocation
import io.github.lukedevops.yukon.proto.DisabledEndpointModule as ProtoDisabledEndpointModule
import io.github.lukedevops.yukon.proto.EndpointDelta as ProtoEndpointDelta
import io.github.lukedevops.yukon.proto.EndpointDiscoverySource as ProtoEndpointDiscoverySource
import io.github.lukedevops.yukon.proto.EndpointLocation as ProtoEndpointLocation
import io.github.lukedevops.yukon.proto.ExternalClass as ProtoExternalClass
import io.github.lukedevops.yukon.proto.GeneratedBy as ProtoGeneratedBy
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
import io.github.lukedevops.yukon.proto.UnreportedClass as ProtoUnreportedClass

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
            .setFinalFlush(batch.finalFlush)
            .addAllDependencyDeltas(batch.dependencyDeltas.map { toProto(it) })
            .build()

    private fun fromProto(batch: ProtoDeltaBatch): DeltaBatch =
        DeltaBatch(
            resource = fromProto(batch.resource),
            deltas = batch.deltasList.map { fromProto(it) },
            endpointDeltas = batch.endpointDeltasList.map { fromProto(it) },
            finalFlush = batch.finalFlush,
            dependencyDeltas = batch.dependencyDeltasList.map { fromProto(it) },
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
                .addAllClassSupertypes(manifest.classSupertypes.map { toProto(it) })
                .addAllUnreportedClasses(manifest.unreportedClasses.map { toProto(it) })
                .addAllDependencies(manifest.dependencies.map { toProto(it) })
                .addAllClassReferences(manifest.classReferences.map { toProto(it) })
                .addAllExternalClasses(manifest.externalClasses.map { toProto(it) })
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
            classSupertypes = manifest.classSupertypesList.map { fromProto(it) },
            unreportedClasses = manifest.unreportedClassesList.map { fromProto(it) },
            dependencies = manifest.dependenciesList.map { fromProto(it) },
            classReferences = manifest.classReferencesList.map { fromProto(it) },
            externalClasses = manifest.externalClassesList.map { fromProto(it) },
        )

    private fun toProto(unreportedClass: UnreportedClass): ProtoUnreportedClass =
        ProtoUnreportedClass
            .newBuilder()
            .setClassName(unreportedClass.className)
            .setFirstSeenUnreportedAt(unreportedClass.firstSeenUnreportedAt)
            .build()

    private fun fromProto(unreportedClass: ProtoUnreportedClass): UnreportedClass =
        UnreportedClass(
            className = unreportedClass.className,
            firstSeenUnreportedAt = unreportedClass.firstSeenUnreportedAt,
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
                .setInline(location.inline)
                .setParameterName(location.parameterName ?: "")
                .setOverridable(location.overridable)
                .setTargetClassName(location.targetClassName ?: "")
                .addAllCalls(location.calls.map { toProto(it) })
                .setInlinedFromClassName(location.inlinedFromClassName ?: "")
                .setGeneratedBy(toProto(location.generatedBy))
                .addAllReferencedClasses(location.referencedClasses)
        location.branchIndex?.let { builder.branchIndex = it }
        location.parameterIndex?.let { builder.parameterIndex = it }
        return builder.build()
    }

    private fun fromProto(location: ProtoProbeLocation): ProbeLocation {
        val kind = fromProto(location.kind)
        return ProbeLocation(
            classId = location.classId,
            probeIndex = location.probeIndex,
            kind = kind,
            className = location.className,
            methodName = location.methodName,
            methodDescriptor = location.methodDescriptor,
            line = location.line,
            branchIndex = if (location.hasBranchIndex()) location.branchIndex else null,
            inline = location.inline,
            parameterIndex = if (location.hasParameterIndex()) location.parameterIndex else null,
            // parameter_name has no wire presence bit: an omission probe's name is "" precisely
            // when its target has no debug info, so the field is only meaningful for that kind.
            parameterName = if (kind == ProbeKind.OPTIONAL_ARGUMENT) location.parameterName else null,
            overridable = location.overridable,
            targetClassName = location.targetClassName.ifEmpty { null },
            calls = location.callsList.map { fromProto(it) },
            inlinedFromClassName = location.inlinedFromClassName.ifEmpty { null },
            generatedBy = fromProto(location.generatedBy),
            referencedClasses = location.referencedClassesList,
        )
    }

    private fun toProto(edge: CallEdge): ProtoCallEdge =
        ProtoCallEdge
            .newBuilder()
            .setClassName(edge.className)
            .setMethodName(edge.methodName)
            .setMethodDescriptor(edge.methodDescriptor)
            .setVirtual(edge.virtual)
            .build()

    private fun fromProto(edge: ProtoCallEdge): CallEdge =
        CallEdge(
            className = edge.className,
            methodName = edge.methodName,
            methodDescriptor = edge.methodDescriptor,
            virtual = edge.virtual,
        )

    private fun toProto(supertypes: ClassSupertypes): ProtoClassSupertypes =
        ProtoClassSupertypes
            .newBuilder()
            .setClassId(supertypes.classId)
            .setSuperClassName(supertypes.superClassName ?: "")
            .addAllInterfaceNames(supertypes.interfaceNames)
            .build()

    private fun fromProto(supertypes: ProtoClassSupertypes): ClassSupertypes =
        ClassSupertypes(
            classId = supertypes.classId,
            superClassName = supertypes.superClassName.ifEmpty { null },
            interfaceNames = supertypes.interfaceNamesList,
        )

    private fun toProto(kind: ProbeKind): ProtoProbeKind =
        when (kind) {
            ProbeKind.METHOD -> ProtoProbeKind.METHOD
            ProbeKind.BRANCH -> ProtoProbeKind.BRANCH
            ProbeKind.OPTIONAL_ARGUMENT -> ProtoProbeKind.OPTIONAL_ARGUMENT
        }

    private fun fromProto(kind: ProtoProbeKind): ProbeKind =
        when (kind) {
            ProtoProbeKind.METHOD -> {
                ProbeKind.METHOD
            }

            ProtoProbeKind.BRANCH -> {
                ProbeKind.BRANCH
            }

            ProtoProbeKind.OPTIONAL_ARGUMENT -> {
                ProbeKind.OPTIONAL_ARGUMENT
            }

            ProtoProbeKind.PROBE_KIND_UNSPECIFIED, ProtoProbeKind.UNRECOGNIZED -> {
                throw IllegalArgumentException("unrecognized probe kind on the wire: $kind")
            }
        }

    private fun toProto(generatedBy: GeneratedBy): ProtoGeneratedBy =
        when (generatedBy) {
            GeneratedBy.NONE -> ProtoGeneratedBy.GENERATED_BY_NONE
            GeneratedBy.ENUM -> ProtoGeneratedBy.ENUM
            GeneratedBy.DATA_CLASS -> ProtoGeneratedBy.DATA_CLASS
            GeneratedBy.DEFAULT_IMPLS -> ProtoGeneratedBy.DEFAULT_IMPLS
            GeneratedBy.RECORD -> ProtoGeneratedBy.RECORD
        }

    // GENERATED_BY_NONE is a legitimate value on the wire, unlike ProbeKind's own unspecified
    // default: it means "not generated", not "never set". Only an enum value this codec does not
    // know about is an error.
    private fun fromProto(generatedBy: ProtoGeneratedBy): GeneratedBy =
        when (generatedBy) {
            ProtoGeneratedBy.GENERATED_BY_NONE -> GeneratedBy.NONE
            ProtoGeneratedBy.ENUM -> GeneratedBy.ENUM
            ProtoGeneratedBy.DATA_CLASS -> GeneratedBy.DATA_CLASS
            ProtoGeneratedBy.DEFAULT_IMPLS -> GeneratedBy.DEFAULT_IMPLS
            ProtoGeneratedBy.RECORD -> GeneratedBy.RECORD
            ProtoGeneratedBy.UNRECOGNIZED -> throw IllegalArgumentException("unrecognized generated-by reason on the wire: $generatedBy")
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
            .addAllExternalClasses(baseline.externalClasses.map { toProto(it) })
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
            externalClasses = baseline.externalClassesList.map { fromProto(it) },
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
            .setSuperClassName(declaredClass.superClassName ?: "")
            .addAllInterfaceNames(declaredClass.interfaceNames)
            .addAllReferencedClasses(declaredClass.referencedClasses)
            .build()

    private fun fromProto(declaredClass: ProtoDeclaredClass): DeclaredClass =
        DeclaredClass(
            className = declaredClass.className,
            methods = declaredClass.methodsList.map { fromProto(it) },
            superClassName = declaredClass.superClassName.ifEmpty { null },
            interfaceNames = declaredClass.interfaceNamesList,
            referencedClasses = declaredClass.referencedClassesList,
        )

    private fun toProto(method: DeclaredMethod): ProtoDeclaredMethod =
        ProtoDeclaredMethod
            .newBuilder()
            .setMethodName(method.methodName)
            .setMethodDescriptor(method.methodDescriptor)
            .setInline(method.inline)
            .addAllCalls(method.calls.map { toProto(it) })
            .setGeneratedBy(toProto(method.generatedBy))
            .addAllReferencedClasses(method.referencedClasses)
            .build()

    private fun fromProto(method: ProtoDeclaredMethod): DeclaredMethod =
        DeclaredMethod(
            methodName = method.methodName,
            methodDescriptor = method.methodDescriptor,
            inline = method.inline,
            calls = method.callsList.map { fromProto(it) },
            generatedBy = fromProto(method.generatedBy),
            referencedClasses = method.referencedClassesList,
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

    private fun toProto(location: DependencyLocation): ProtoDependencyLocation {
        val builder =
            ProtoDependencyLocation
                .newBuilder()
                .setDependencyId(location.dependencyId)
                .addAllIdentities(location.identities.map { toProto(it) })
                .setIdentitySource(toProto(location.identitySource))
                .setLocation(location.location)
                .setDiscoverySource(toProto(location.discoverySource))
        location.classCount?.let { builder.classCount = it }
        return builder.build()
    }

    private fun fromProto(location: ProtoDependencyLocation): DependencyLocation =
        DependencyLocation(
            dependencyId = location.dependencyId,
            identities = location.identitiesList.map { fromProto(it) },
            identitySource = fromProto(location.identitySource),
            location = location.location,
            discoverySource = fromProto(location.discoverySource),
            classCount = if (location.hasClassCount()) location.classCount else null,
        )

    private fun toProto(identity: DependencyIdentity): ProtoDependencyIdentity =
        ProtoDependencyIdentity
            .newBuilder()
            .setGroupId(identity.groupId ?: "")
            .setArtifactId(identity.artifactId)
            .setVersion(identity.version ?: "")
            .build()

    private fun fromProto(identity: ProtoDependencyIdentity): DependencyIdentity =
        DependencyIdentity(
            groupId = identity.groupId.ifEmpty { null },
            artifactId = identity.artifactId,
            version = identity.version.ifEmpty { null },
        )

    private fun toProto(source: DependencyIdentitySource): ProtoDependencyIdentitySource =
        when (source) {
            DependencyIdentitySource.POM_PROPERTIES -> ProtoDependencyIdentitySource.POM_PROPERTIES
            DependencyIdentitySource.JAR_MANIFEST -> ProtoDependencyIdentitySource.JAR_MANIFEST
            DependencyIdentitySource.FILENAME -> ProtoDependencyIdentitySource.FILENAME
        }

    private fun fromProto(source: ProtoDependencyIdentitySource): DependencyIdentitySource =
        when (source) {
            ProtoDependencyIdentitySource.POM_PROPERTIES -> {
                DependencyIdentitySource.POM_PROPERTIES
            }

            ProtoDependencyIdentitySource.JAR_MANIFEST -> {
                DependencyIdentitySource.JAR_MANIFEST
            }

            ProtoDependencyIdentitySource.FILENAME -> {
                DependencyIdentitySource.FILENAME
            }

            ProtoDependencyIdentitySource.DEPENDENCY_IDENTITY_SOURCE_UNSPECIFIED, ProtoDependencyIdentitySource.UNRECOGNIZED -> {
                throw IllegalArgumentException("unrecognized dependency identity source on the wire: $source")
            }
        }

    private fun toProto(source: DependencyDiscoverySource): ProtoDependencyDiscoverySource =
        when (source) {
            DependencyDiscoverySource.STARTUP_CLASSPATH -> ProtoDependencyDiscoverySource.STARTUP_CLASSPATH
            DependencyDiscoverySource.LOAD -> ProtoDependencyDiscoverySource.LOAD
        }

    private fun fromProto(source: ProtoDependencyDiscoverySource): DependencyDiscoverySource =
        when (source) {
            ProtoDependencyDiscoverySource.STARTUP_CLASSPATH -> {
                DependencyDiscoverySource.STARTUP_CLASSPATH
            }

            ProtoDependencyDiscoverySource.LOAD -> {
                DependencyDiscoverySource.LOAD
            }

            ProtoDependencyDiscoverySource.DEPENDENCY_DISCOVERY_SOURCE_UNSPECIFIED, ProtoDependencyDiscoverySource.UNRECOGNIZED -> {
                throw IllegalArgumentException("unrecognized dependency discovery source on the wire: $source")
            }
        }

    private fun toProto(delta: DependencyDelta): ProtoDependencyDelta =
        ProtoDependencyDelta
            .newBuilder()
            .setDependencyId(delta.dependencyId)
            .setFirstLoadedAt(delta.firstLoadedAt)
            .setLoadedClassesTotal(delta.loadedClassesTotal)
            .build()

    private fun fromProto(delta: ProtoDependencyDelta): DependencyDelta =
        DependencyDelta(
            dependencyId = delta.dependencyId,
            firstLoadedAt = delta.firstLoadedAt,
            loadedClassesTotal = delta.loadedClassesTotal,
        )

    private fun toProto(references: ClassReferences): ProtoClassReferences =
        ProtoClassReferences
            .newBuilder()
            .setClassId(references.classId)
            .addAllReferencedClasses(references.referencedClasses)
            .build()

    private fun fromProto(references: ProtoClassReferences): ClassReferences =
        ClassReferences(
            classId = references.classId,
            referencedClasses = references.referencedClassesList,
        )

    private fun toProto(externalClass: ExternalClass): ProtoExternalClass {
        val builder =
            ProtoExternalClass
                .newBuilder()
                .setClassName(externalClass.className)
                .setAbsent(externalClass.absent)
        externalClass.dependencyId?.let { builder.dependencyId = it }
        return builder.build()
    }

    private fun fromProto(externalClass: ProtoExternalClass): ExternalClass =
        ExternalClass(
            className = externalClass.className,
            dependencyId = if (externalClass.hasDependencyId()) externalClass.dependencyId else null,
            absent = externalClass.absent,
        )
}
