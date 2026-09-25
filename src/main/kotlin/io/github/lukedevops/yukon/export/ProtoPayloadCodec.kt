package io.github.lukedevops.yukon.export

import io.github.lukedevops.yukon.proto.BodyKind as ProtoBodyKind
import io.github.lukedevops.yukon.proto.BranchOutcome as ProtoBranchOutcome
import io.github.lukedevops.yukon.proto.BranchRole as ProtoBranchRole
import io.github.lukedevops.yukon.proto.BranchSite as ProtoBranchSite
import io.github.lukedevops.yukon.proto.CallEdge as ProtoCallEdge
import io.github.lukedevops.yukon.proto.CallEdgeKind as ProtoCallEdgeKind
import io.github.lukedevops.yukon.proto.ClassLocation as ProtoClassLocation
import io.github.lukedevops.yukon.proto.ClassReferences as ProtoClassReferences
import io.github.lukedevops.yukon.proto.ConditionPart as ProtoConditionPart
import io.github.lukedevops.yukon.proto.ConditionPartKind as ProtoConditionPartKind
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
import io.github.lukedevops.yukon.proto.KotlinKind as ProtoKotlinKind
import io.github.lukedevops.yukon.proto.LineRange as ProtoLineRange
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
                .setRunId(resource.runId)
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
            runId = resource.runId,
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

    private fun toProto(manifest: ProbeManifest): ProtoProbeManifest =
        ProtoProbeManifest
            .newBuilder()
            .setResource(toProto(manifest.resource))
            .addAllProbes(manifest.probes.map { toProto(it) })
            .addAllSkippedClasses(manifest.skippedClasses.map { toProto(it) })
            .addAllEndpoints(manifest.endpoints.map { toProto(it) })
            .addAllDisabledEndpointModules(manifest.disabledEndpointModules.map { toProto(it) })
            .addAllClassLocations(manifest.classLocations.map { toProto(it) })
            .addAllUnreportedClasses(manifest.unreportedClasses.map { toProto(it) })
            .addAllDependencies(manifest.dependencies.map { toProto(it) })
            .addAllClassReferences(manifest.classReferences.map { toProto(it) })
            .addAllExternalClasses(manifest.externalClasses.map { toProto(it) })
            .setReferencesRecorded(manifest.referencesRecorded)
            .setDependenciesListed(manifest.dependenciesListed)
            .build()

    private fun fromProto(manifest: ProtoProbeManifest): ProbeManifest =
        ProbeManifest(
            resource = fromProto(manifest.resource),
            probes = manifest.probesList.map { fromProto(it) },
            skippedClasses = manifest.skippedClassesList.map { fromProto(it) },
            endpoints = manifest.endpointsList.map { fromProto(it) },
            disabledEndpointModules = manifest.disabledEndpointModulesList.map { fromProto(it) },
            classLocations = manifest.classLocationsList.map { fromProto(it) },
            unreportedClasses = manifest.unreportedClassesList.map { fromProto(it) },
            dependencies = manifest.dependenciesList.map { fromProto(it) },
            classReferences = manifest.classReferencesList.map { fromProto(it) },
            externalClasses = manifest.externalClassesList.map { fromProto(it) },
            referencesRecorded = manifest.referencesRecorded,
            dependenciesListed = manifest.dependenciesListed,
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
                .setLambdaBody(location.lambdaBody)
                .addAllBranchSites(location.branchSites.map { toProto(it) })
                .setStatic(location.static)
        location.branchIndex?.let { builder.branchIndex = it }
        location.parameterIndex?.let { builder.parameterIndex = it }
        location.branchKey?.let { builder.branchKey = it }
        location.siteIndex?.let { builder.siteIndex = it }
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
            branchKey = if (location.hasBranchKey()) location.branchKey else null,
            lambdaBody = location.lambdaBody,
            branchSites = location.branchSitesList.map { fromProto(it) },
            siteIndex = if (location.hasSiteIndex()) location.siteIndex else null,
            static = location.static,
        )
    }

    private fun toProto(site: BranchSite): ProtoBranchSite {
        val builder =
            ProtoBranchSite
                .newBuilder()
                .setSiteIndex(site.siteIndex)
                .setLine(site.line)
                .addAllOutcomes(site.outcomes.map { toProto(it) })
                .addAllCondition(site.condition.map { toProto(it) })
        site.siteKey?.let { builder.siteKey = it }
        site.guard?.let { builder.guard = it }
        return builder.build()
    }

    private fun fromProto(site: ProtoBranchSite): BranchSite =
        BranchSite(
            siteIndex = site.siteIndex,
            siteKey = if (site.hasSiteKey()) site.siteKey else null,
            line = site.line,
            outcomes = site.outcomesList.map { fromProto(it) },
            guard = if (site.hasGuard()) site.guard else null,
            condition = site.conditionList.map { fromProto(it) },
        )

    private fun toProto(part: ConditionPart): ProtoConditionPart =
        ProtoConditionPart
            .newBuilder()
            .setKind(
                when (part.kind) {
                    ConditionPartKind.CODE -> ProtoConditionPartKind.CODE
                    ConditionPartKind.STRING_LITERAL -> ProtoConditionPartKind.STRING_LITERAL
                    ConditionPartKind.PLACEHOLDER -> ProtoConditionPartKind.PLACEHOLDER
                },
            ).setText(part.text)
            .build()

    private fun fromProto(part: ProtoConditionPart): ConditionPart {
        val kind =
            when (part.kind) {
                ProtoConditionPartKind.CODE -> {
                    ConditionPartKind.CODE
                }

                ProtoConditionPartKind.STRING_LITERAL -> {
                    ConditionPartKind.STRING_LITERAL
                }

                ProtoConditionPartKind.PLACEHOLDER -> {
                    ConditionPartKind.PLACEHOLDER
                }

                ProtoConditionPartKind.CONDITION_PART_KIND_UNSPECIFIED, ProtoConditionPartKind.UNRECOGNIZED -> {
                    throw IllegalArgumentException("unrecognized condition part kind on the wire: ${part.kind}")
                }
            }
        return ConditionPart(kind, part.text)
    }

    private fun toProto(outcome: BranchOutcome): ProtoBranchOutcome {
        val builder =
            ProtoBranchOutcome
                .newBuilder()
                .setBranchIndex(outcome.branchIndex)
                .setRole(toProto(outcome.role))
                .addAllGuardedLines(outcome.guardedLines.map { toProto(it) })
                .addAllPartlyGuardedLines(outcome.partlyGuardedLines.map { toProto(it) })
                .addAllCaseLabel(outcome.caseLabel.map { toProto(it) })
        outcome.caseKey?.let { builder.caseKey = it }
        return builder.build()
    }

    private fun fromProto(outcome: ProtoBranchOutcome): BranchOutcome =
        BranchOutcome(
            branchIndex = outcome.branchIndex,
            role = fromProto(outcome.role),
            caseKey = if (outcome.hasCaseKey()) outcome.caseKey else null,
            guardedLines = outcome.guardedLinesList.map { fromProto(it) },
            partlyGuardedLines = outcome.partlyGuardedLinesList.map { fromProto(it) },
            caseLabel = outcome.caseLabelList.map { fromProto(it) },
        )

    private fun toProto(range: LineRange): ProtoLineRange =
        ProtoLineRange
            .newBuilder()
            .setSourceFile(range.sourceFile)
            .setFirstLine(range.firstLine)
            .setLastLine(range.lastLine)
            .build()

    private fun fromProto(range: ProtoLineRange): LineRange = LineRange(range.sourceFile, range.firstLine, range.lastLine)

    private fun toProto(role: BranchRole): ProtoBranchRole =
        when (role) {
            BranchRole.TAKEN -> ProtoBranchRole.TAKEN
            BranchRole.FALL_THROUGH -> ProtoBranchRole.FALL_THROUGH
            BranchRole.CASE -> ProtoBranchRole.CASE
            BranchRole.DEFAULT -> ProtoBranchRole.DEFAULT
        }

    private fun fromProto(role: ProtoBranchRole): BranchRole =
        when (role) {
            ProtoBranchRole.TAKEN -> {
                BranchRole.TAKEN
            }

            ProtoBranchRole.FALL_THROUGH -> {
                BranchRole.FALL_THROUGH
            }

            ProtoBranchRole.CASE -> {
                BranchRole.CASE
            }

            ProtoBranchRole.DEFAULT -> {
                BranchRole.DEFAULT
            }

            ProtoBranchRole.BRANCH_ROLE_UNSPECIFIED, ProtoBranchRole.UNRECOGNIZED -> {
                throw IllegalArgumentException("unrecognized branch role on the wire: $role")
            }
        }

    private fun toProto(edge: CallEdge): ProtoCallEdge {
        val builder =
            ProtoCallEdge
                .newBuilder()
                .setClassName(edge.className)
                .setMethodName(edge.methodName)
                .setMethodDescriptor(edge.methodDescriptor)
                .setVirtual(edge.virtual)
                .setKind(toProto(edge.kind))
                .setCapturedCount(edge.capturedCount)
        edge.guard?.let { builder.guard = it }
        return builder.build()
    }

    private fun fromProto(edge: ProtoCallEdge): CallEdge =
        CallEdge(
            className = edge.className,
            methodName = edge.methodName,
            methodDescriptor = edge.methodDescriptor,
            virtual = edge.virtual,
            kind = fromProto(edge.kind),
            capturedCount = edge.capturedCount,
            guard = if (edge.hasGuard()) edge.guard else null,
        )

    private fun toProto(kind: CallEdgeKind): ProtoCallEdgeKind =
        when (kind) {
            CallEdgeKind.CALL -> ProtoCallEdgeKind.CALL
            CallEdgeKind.CREATES -> ProtoCallEdgeKind.CREATES
        }

    private fun fromProto(kind: ProtoCallEdgeKind): CallEdgeKind =
        when (kind) {
            ProtoCallEdgeKind.CALL -> CallEdgeKind.CALL
            ProtoCallEdgeKind.CREATES -> CallEdgeKind.CREATES
            ProtoCallEdgeKind.UNRECOGNIZED -> throw IllegalArgumentException("unrecognized call edge kind on the wire: $kind")
        }

    private fun toProto(location: ClassLocation): ProtoClassLocation =
        ProtoClassLocation
            .newBuilder()
            .setClassId(location.classId)
            .setSuperClassName(location.superClassName ?: "")
            .addAllInterfaceNames(location.interfaceNames)
            .setSourceFile(location.sourceFile ?: "")
            .setBodyKind(toProto(location.bodyKind))
            .setSourceName(location.sourceName ?: "")
            .setKotlinKind(toProto(location.kotlinKind))
            .build()

    private fun fromProto(location: ProtoClassLocation): ClassLocation =
        ClassLocation(
            classId = location.classId,
            superClassName = location.superClassName.ifEmpty { null },
            interfaceNames = location.interfaceNamesList,
            sourceFile = location.sourceFile.ifEmpty { null },
            bodyKind = fromProto(location.bodyKind),
            sourceName = location.sourceName.ifEmpty { null },
            kotlinKind = fromProto(location.kotlinKind),
        )

    private fun toProto(kind: KotlinKind): ProtoKotlinKind =
        when (kind) {
            KotlinKind.NONE -> ProtoKotlinKind.KOTLIN_KIND_NONE
            KotlinKind.KOTLIN_CLASS -> ProtoKotlinKind.KOTLIN_CLASS
            KotlinKind.FILE_FACADE -> ProtoKotlinKind.FILE_FACADE
            KotlinKind.SYNTHETIC_CLASS -> ProtoKotlinKind.SYNTHETIC_CLASS
            KotlinKind.MULTIFILE_CLASS_FACADE -> ProtoKotlinKind.MULTIFILE_CLASS_FACADE
            KotlinKind.MULTIFILE_CLASS_PART -> ProtoKotlinKind.MULTIFILE_CLASS_PART
        }

    private fun fromProto(kind: ProtoKotlinKind): KotlinKind =
        when (kind) {
            ProtoKotlinKind.KOTLIN_KIND_NONE -> KotlinKind.NONE
            ProtoKotlinKind.KOTLIN_CLASS -> KotlinKind.KOTLIN_CLASS
            ProtoKotlinKind.FILE_FACADE -> KotlinKind.FILE_FACADE
            ProtoKotlinKind.SYNTHETIC_CLASS -> KotlinKind.SYNTHETIC_CLASS
            ProtoKotlinKind.MULTIFILE_CLASS_FACADE -> KotlinKind.MULTIFILE_CLASS_FACADE
            ProtoKotlinKind.MULTIFILE_CLASS_PART -> KotlinKind.MULTIFILE_CLASS_PART
            ProtoKotlinKind.UNRECOGNIZED -> throw IllegalArgumentException("unrecognized Kotlin kind on the wire: $kind")
        }

    private fun toProto(kind: BodyKind): ProtoBodyKind =
        when (kind) {
            BodyKind.NONE -> ProtoBodyKind.NONE
            BodyKind.ANONYMOUS_CLASS -> ProtoBodyKind.ANONYMOUS_CLASS
            BodyKind.OBJECT_EXPRESSION -> ProtoBodyKind.OBJECT_EXPRESSION
            BodyKind.LOCAL_CLASS -> ProtoBodyKind.LOCAL_CLASS
            BodyKind.LAMBDA_CLASS -> ProtoBodyKind.LAMBDA_CLASS
        }

    private fun fromProto(kind: ProtoBodyKind): BodyKind =
        when (kind) {
            ProtoBodyKind.NONE -> BodyKind.NONE
            ProtoBodyKind.ANONYMOUS_CLASS -> BodyKind.ANONYMOUS_CLASS
            ProtoBodyKind.OBJECT_EXPRESSION -> BodyKind.OBJECT_EXPRESSION
            ProtoBodyKind.LOCAL_CLASS -> BodyKind.LOCAL_CLASS
            ProtoBodyKind.LAMBDA_CLASS -> BodyKind.LAMBDA_CLASS
            ProtoBodyKind.UNRECOGNIZED -> throw IllegalArgumentException("unrecognized body kind on the wire: $kind")
        }

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
            GeneratedBy.JVM_OVERLOADS -> ProtoGeneratedBy.JVM_OVERLOADS
            GeneratedBy.MULTIFILE_FACADE -> ProtoGeneratedBy.MULTIFILE_FACADE
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
            ProtoGeneratedBy.JVM_OVERLOADS -> GeneratedBy.JVM_OVERLOADS
            ProtoGeneratedBy.MULTIFILE_FACADE -> GeneratedBy.MULTIFILE_FACADE
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
            .setSourceFile(declaredClass.sourceFile ?: "")
            .setBodyKind(toProto(declaredClass.bodyKind))
            .setSourceName(declaredClass.sourceName ?: "")
            .setKotlinKind(toProto(declaredClass.kotlinKind))
            .build()

    private fun fromProto(declaredClass: ProtoDeclaredClass): DeclaredClass =
        DeclaredClass(
            className = declaredClass.className,
            methods = declaredClass.methodsList.map { fromProto(it) },
            superClassName = declaredClass.superClassName.ifEmpty { null },
            interfaceNames = declaredClass.interfaceNamesList,
            referencedClasses = declaredClass.referencedClassesList,
            sourceFile = declaredClass.sourceFile.ifEmpty { null },
            bodyKind = fromProto(declaredClass.bodyKind),
            sourceName = declaredClass.sourceName.ifEmpty { null },
            kotlinKind = fromProto(declaredClass.kotlinKind),
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
            .setLambdaBody(method.lambdaBody)
            .addAllBranchSites(method.branchSites.map { toProto(it) })
            .setStatic(method.static)
            .build()

    private fun fromProto(method: ProtoDeclaredMethod): DeclaredMethod =
        DeclaredMethod(
            methodName = method.methodName,
            methodDescriptor = method.methodDescriptor,
            inline = method.inline,
            calls = method.callsList.map { fromProto(it) },
            generatedBy = fromProto(method.generatedBy),
            referencedClasses = method.referencedClassesList,
            lambdaBody = method.lambdaBody,
            branchSites = method.branchSitesList.map { fromProto(it) },
            static = method.static,
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
