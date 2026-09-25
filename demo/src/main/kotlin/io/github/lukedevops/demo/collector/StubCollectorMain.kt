package io.github.lukedevops.demo.collector

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.lukedevops.demo.DemoPorts
import io.github.lukedevops.yukon.proto.BranchRole
import io.github.lukedevops.yukon.proto.BranchSite
import io.github.lukedevops.yukon.proto.CallEdge
import io.github.lukedevops.yukon.proto.CallEdgeKind
import io.github.lukedevops.yukon.proto.ConditionPart
import io.github.lukedevops.yukon.proto.ConditionPartKind
import io.github.lukedevops.yukon.proto.DeltaBatch
import io.github.lukedevops.yukon.proto.EndpointDiscoverySource
import io.github.lukedevops.yukon.proto.GeneratedBy
import io.github.lukedevops.yukon.proto.KotlinKind
import io.github.lukedevops.yukon.proto.LineRange
import io.github.lukedevops.yukon.proto.ProbeKind
import io.github.lukedevops.yukon.proto.ProbeManifest
import io.github.lukedevops.yukon.proto.ResourceAttributes
import io.github.lukedevops.yukon.proto.StaticBaseline
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * One run of one instance: the pair every payload's resource names. Class ids, endpoint ids and
 * dependency ids are assigned by each process in its own order, and a restart under a pinned
 * instance id starts a new process, so every key below is scoped to a run, not to an instance
 * alone. Report lines still show [serviceInstanceId], the name a person knows. See ADR 0032.
 */
private data class Run(
    val serviceInstanceId: String,
    val runId: String,
)

/**
 * [classId] is assigned independently by each process's own registry, in that process's own
 * class-loading order, so the same [classId] can mean a different class in two different runs.
 * Every probe key used by this stub collector is scoped to a [Run] for that reason.
 */
private data class InstanceProbeKey(
    val run: Run,
    val classId: Int,
    val probeIndex: Int,
)

/** Scopes a skipped class's name to the run that reported it, for the same reason as [InstanceProbeKey]. */
private data class InstanceClassKey(
    val run: Run,
    val className: String,
)

/** Scopes an endpoint id to the run that reported it, for the same reason as [InstanceProbeKey]. */
private data class InstanceEndpointKey(
    val run: Run,
    val endpointId: Int,
)

/** Scopes a disabled endpoint module's name to the run that reported it. */
private data class InstanceModuleKey(
    val run: Run,
    val module: String,
)

/**
 * Groups every omission probe naming one optional parameter, within one run: the target
 * class (`targetClassName ?: className`), method, descriptor, and parameter index. A group can
 * hold more than one probe: a Scala constructor default gets both a module getter, resolved
 * across the class boundary, and that class's own static forwarder for the same getter name,
 * resolved in class, both landing on the same target. See ADR 0023.
 */
private data class OmissionTargetKey(
    val run: Run,
    val targetClassName: String,
    val methodName: String,
    val methodDescriptor: String,
    val parameterIndex: Int?,
)

private data class ProbeInfo(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val line: Int,
    val kind: ProbeKind,
    val branchIndex: Int?,
    val inline: Boolean,
    val parameterIndex: Int? = null,
    val parameterName: String? = null,
    val overridable: Boolean = false,
    val targetClassName: String? = null,
    val inlinedFromClassName: String? = null,
    val generatedBy: GeneratedBy = GeneratedBy.GENERATED_BY_NONE,
    val siteIndex: Int? = null,
    val static: Boolean = false,
    val lambdaBody: Boolean = false,
    val parameterNames: List<String> = emptyList(),
    val genericSignature: String = "",
    val extensionReceiver: Boolean = false,
)

/** One method of one run: where a METHOD probe's branch sites are kept, for its BRANCH probes to find. See ADR 0037. */
private data class InstanceMethodKey(
    val run: Run,
    val classId: Int,
    val methodName: String,
    val methodDescriptor: String,
)

/**
 * One call edge read from a METHOD probe's own bytecode. See ADR 0024. [guard] is the branch index,
 * in the caller's class, of the innermost outcome that must run before the call, or null when none
 * does. See ADR 0037. [creates] is true for a CREATES edge, which hands the callee to someone else
 * to run. See ADR 0028. [implementedInterface] is the dotted interface a CREATES edge from an
 * invokedynamic implements, or null. See ADR 0042.
 */
private data class CallEdgeInfo(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val virtual: Boolean,
    val guard: Int? = null,
    val creates: Boolean = false,
    val implementedInterface: String? = null,
)

/**
 * What a report needs to name a class the way a person knows it: its source file and the kind
 * kotlinc gives it. See ADR 0041.
 */
private data class ClassNaming(
    val sourceFile: String?,
    val kotlinKind: KotlinKind,
)

/** A class's superclass and direct interfaces, as reported by one instance. See ADR 0024. */
private data class SupertypesInfo(
    val superClassName: String?,
    val interfaceNames: List<String>,
)

/** Scopes a class_id's class location record to the run that reported it, for the same reason as [InstanceProbeKey]. */
private data class InstanceClassIdKey(
    val run: Run,
    val classId: Int,
)

private data class SkippedInfo(
    val reason: String,
    val skippedAt: Long,
)

private data class DeclaredMethodInfo(
    val methodName: String,
    val methodDescriptor: String,
    val inline: Boolean,
    val calls: List<CallEdgeInfo> = emptyList(),
    val generatedBy: GeneratedBy = GeneratedBy.GENERATED_BY_NONE,
    val referencedClasses: List<String> = emptyList(),
    val static: Boolean = false,
    val parameterNames: List<String> = emptyList(),
    val genericSignature: String = "",
    val extensionReceiver: Boolean = false,
)

/** Scopes a dependency id to the run that reported it, for the same reason as [InstanceProbeKey]. */
private data class InstanceDependencyKey(
    val run: Run,
    val dependencyId: Int,
)

/**
 * One instance's static-baseline references for one declared class: the class-level list and each
 * declared method's. Kept per instance, unlike [staticallyDeclaredClasses], since ADR 0030 splits
 * unreferenced from unreached only with a complete baseline from each instance.
 */
private data class BaselineReferences(
    val classReferences: List<String>,
    val methods: List<DeclaredMethodInfo>,
)

private data class EndpointInfo(
    val verb: String,
    val routeTemplate: String,
    val verbatimTemplate: String,
    val framework: String,
    val discoverySource: EndpointDiscoverySource,
    val handlerClass: String?,
    val handlerMethod: String?,
    val handlerDescriptor: String?,
)

private val manifestProbes = ConcurrentHashMap<InstanceProbeKey, ProbeInfo>()
private val manifestBranchSites = ConcurrentHashMap<InstanceMethodKey, List<BranchSite>>()
private val everHit = Collections.newSetFromMap(ConcurrentHashMap<InstanceProbeKey, Boolean>())
private val skippedClasses = ConcurrentHashMap<InstanceClassKey, SkippedInfo>()

// Call edges (ADR 0024), stored per METHOD probe rather than only counted at receipt, since a
// later chunk's cluster logic needs the actual callees, not just how many arrived.
private val manifestCallEdges = ConcurrentHashMap<InstanceProbeKey, List<CallEdgeInfo>>()
private val supertypesByClassId = ConcurrentHashMap<InstanceClassIdKey, SupertypesInfo>()

// Each class's naming, by name, from any manifest's class locations or any baseline's declared
// classes. A class's source file and kind are the same in every run of one build.
private val classNamingByClassName = ConcurrentHashMap<String, ClassNaming>()

// hits_total is cumulative from process start, not the count since the last flush. Merging with
// max() is what makes this safe against a re-delivered or reordered batch: applying the same or
// an older value again is a no-op instead of double-counting.
private val latestHitsTotal = ConcurrentHashMap<InstanceProbeKey, Long>()

// An endpoint record can be re-sent after its first delivery (a discovery-source upgrade, a
// handler join learned later), unlike a class's probe locations, so this upserts rather than
// only ever inserting once.
private val manifestEndpoints = ConcurrentHashMap<InstanceEndpointKey, EndpointInfo>()
private val disabledEndpointModules = ConcurrentHashMap<InstanceModuleKey, String>()

// Same cumulative, max()-merged semantics as latestHitsTotal, for endpoint hit counts.
private val latestEndpointHitsTotal = ConcurrentHashMap<InstanceEndpointKey, Long>()

// Any class name the reactive manifest has ever mentioned, whether it got probes or was skipped.
// Either way, it was loaded and reached the transform stage - the opposite of what the static
// baseline's declared-classes set is for.
private val dynamicallyKnownClassNames = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

/** Every run any delta batch has ever arrived from, heartbeat included. See ADR 0010. */
private val allRuns = Collections.newSetFromMap(ConcurrentHashMap<Run, Boolean>())

/** Runs whose shutdown hook has sent a delta batch with `final_flush` set. See ADR 0010. */
private val runsThatEndedCleanly = Collections.newSetFromMap(ConcurrentHashMap<Run, Boolean>())
private val staticallyDeclaredClasses = ConcurrentHashMap<String, List<DeclaredMethodInfo>>()

// A declared class's superclass and interfaces, read the same way as a loaded class's
// ClassLocation record. See ADR 0024.
private val staticallyDeclaredSupertypes = ConcurrentHashMap<String, SupertypesInfo>()
private val staticallyUnsafeClasses = ConcurrentHashMap<String, String>()
private val staticallyUnreadableClasses = ConcurrentHashMap<String, String>()
private val staticallyUnprobedClasses = ConcurrentHashMap<String, String>()

/** One static scan, identified by (run, scanned_at), arrives as chunk_count chunks; only a complete scan may be diffed. */
private data class ScanKey(
    val run: Run,
    val scannedAt: Long,
)

private data class ScanProgress(
    val chunkCount: Int,
    val received: MutableSet<Int> = ConcurrentHashMap.newKeySet(),
) {
    val complete: Boolean get() = received.size == chunkCount
}

private val scans = ConcurrentHashMap<ScanKey, ScanProgress>()

// Dependency usage (ADR 0030), all per run: dependency_id and class_id are assigned by each
// process's own registry.
private val dependencyLocations = ConcurrentHashMap<InstanceDependencyKey, DependencyView>()

// Cumulative distinct class names, max()-merged like latestHitsTotal.
private val latestLoadedClassesTotal = ConcurrentHashMap<InstanceDependencyKey, Long>()
private val firstLoadedAt = ConcurrentHashMap<InstanceDependencyKey, Long>()
private val externalClasses = ConcurrentHashMap<InstanceClassKey, ExternalClassView>()
private val probeReferencedClasses = ConcurrentHashMap<InstanceProbeKey, List<String>>()
private val classLevelReferences = ConcurrentHashMap<InstanceClassIdKey, List<String>>()
private val baselineReferences = ConcurrentHashMap<InstanceClassKey, BaselineReferences>()

/** Runs any manifest arrived from with `references_recorded` set. See ADR 0030. */
private val runsRecordingReferences = Collections.newSetFromMap(ConcurrentHashMap<Run, Boolean>())

/** Runs any manifest arrived from with `dependencies_listed` set. See ADR 0036. */
private val runsWithDependenciesListed = Collections.newSetFromMap(ConcurrentHashMap<Run, Boolean>())
private val manifestRuns = Collections.newSetFromMap(ConcurrentHashMap<Run, Boolean>())

/** One node of the call graph [computeUnreachedClusters] resolves: a probed method, by identity alone. See ADR 0024. */
private data class NodeKey(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
)

/**
 * A [NodeKey]'s reporting fields and raw, unresolved outgoing call edges. [neverLoaded] marks a
 * node that exists only because a complete static baseline declared it, never a manifest: its
 * [hits] is fixed at zero, since the dynamic tier never registered its class at all.
 */
private data class NodeInfo(
    val line: Int,
    val neverLoaded: Boolean,
    val hits: Long,
    val edges: Set<CallEdgeInfo>,
)

/** One resolved call out of a method: the callee node and the guard the raw [CallEdgeInfo] carried. */
private data class ResolvedCall(
    val callee: NodeKey,
    val guard: Int?,
)

/** The resolved call graph: every node and its resolved outgoing calls. */
private class CallGraph(
    val nodes: Map<NodeKey, NodeInfo>,
    val calls: Map<NodeKey, Set<ResolvedCall>>,
)

/**
 * One node of the cluster graph: a method, or, when [branchIndex] is set, an outcome node in that
 * method. See ADR 0039. When [isClass] is true it is a class node, and [method] holds only the class
 * name. See [classNode].
 */
private data class ClusterNode(
    val method: NodeKey,
    val branchIndex: Int? = null,
    val isClass: Boolean = false,
)

/** The class node for [className]. */
private fun classNode(className: String) = ClusterNode(NodeKey(className, "", ""), isClass = true)

/** A class finding, as a class node and a report name it. See server ADR 0034. */
private enum class ClassFinding(
    val text: String,
) {
    NEVER_LOADED("never loaded"),
    NEVER_INITIALISED("never initialised"),
    NEVER_INSTANTIATED("never instantiated"),
}

/**
 * A class node: a class that holds a class finding, standing for the never-hit [methods] the
 * finding covers. Those methods are never nodes of their own. See server ADR 0034.
 */
private class ClassNodeInfo(
    val finding: ClassFinding,
    val methods: List<NodeKey>,
)

/**
 * What server ADR 0034's rules say about the loaded classes. [findings] holds each class that is
 * never initialised or never instantiated. [covered] names the never-hit methods a finding covers,
 * which can only run through it, lambda bodies that fold into it included. [inNeverHitCode] names
 * the lambda bodies that fold into never-hit methods that are rows of their own. [constructed]
 * holds each class one of whose constructors ran, so a never-hit constructor of it is an unused
 * overload.
 */
private class ClassJudgement(
    val findings: Map<String, ClassFinding>,
    val covered: Map<String, List<NodeKey>>,
    val inNeverHitCode: Set<NodeKey>,
    val constructed: Set<String>,
) {
    val coveredMethods: Set<NodeKey> = covered.values.flatten().toSet()
}

/** One judgeable method merged across runs: [hits] summed, [static] and [lambdaBody] true when any run's probe said so. */
private data class MethodFacts(
    val hits: Long,
    val static: Boolean,
    val lambdaBody: Boolean,
)

/** A class's static initialiser, a class state and never a method row. See server ADR 0034. */
private const val CLASS_INIT = "<clinit>"

/** A constructor's method name. */
private const val CONSTRUCTOR = "<init>"

/**
 * An outcome node: a judgeable outcome with no hits in a method with hits. [line] is its BRANCH
 * probe's line. [site] is the site that lists it on its method's METHOD probe, or null when no
 * manifest listed one.
 */
private data class OutcomeNode(
    val line: Int,
    val site: BranchSite?,
)

/** The cluster graph: each node's callers and callees, over methods and outcome nodes alike. */
private class ClusterGraph(
    val callersOf: Map<ClusterNode, Set<ClusterNode>>,
    val calleesOf: Map<ClusterNode, Set<ClusterNode>>,
)

/** Which of the four root shapes ADR 0024, ADR 0039 and server ADR 0034 distinguish an [UnreachedClusterInfo] by. */
private enum class ClusterRootKind { REACHED_FROM_HIT, UNCALLED, UNTAKEN_OUTCOME, CLASS_FINDING }

/** One member of an unreached cluster, printed by [printUnreachedClusterReport]. */
private data class ClusterMember(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val neverLoaded: Boolean,
)

/**
 * A root plus every never-hit method reachable from it whose every in-scope caller is itself in the
 * cluster. [root] is the root method, or for an untaken outcome root the method that holds it, or
 * for a class root the class alone, with an empty method name and descriptor. [rootOutcome] is set
 * only for an untaken outcome root, and [rootFinding] only for a class root. [reachedFrom] lists the
 * methods with hits that call a root reached from hit or a class root. [wholeClasses] lists each
 * class the cluster holds whole, and [members] every other method. Neither lists `<clinit>`.
 */
private data class UnreachedClusterInfo(
    val root: ClusterMember,
    val rootKind: ClusterRootKind,
    val members: List<ClusterMember>,
    val neverLoadedClasses: Int,
    val rootOutcome: RootOutcome? = null,
    val reachedFrom: List<ClusterMember> = emptyList(),
    val rootFinding: ClassFinding? = null,
    val wholeClasses: List<WholeClassInfo> = emptyList(),
) {
    /** How many methods the cluster holds, never counting `<clinit>`. */
    val membersTotal: Int get() = members.size + wholeClasses.sumOf { it.methodsTotal }
}

/** One class a cluster holds whole: every method node of it. [finding] is null when it holds none. */
private data class WholeClassInfo(
    val className: String,
    val finding: ClassFinding?,
    val methodsTotal: Int,
)

/** The untaken outcome that roots a cluster: its branch index, its BRANCH probe's line, and its site if a manifest listed one. */
private data class RootOutcome(
    val branchIndex: Int,
    val line: Int,
    val site: BranchSite?,
)

/**
 * Stands in for the real collector, which lives outside this repo.
 *
 * It decodes the same generated protobuf classes the agent sends. It keeps the manifest and
 * hit history in memory. On shutdown, it prints two reports: "never hit" (manifest probes with
 * no delta that ever reported a hit) and, since the demo server opts into
 * `staticBaselineEnabled=true`, "never loaded" (classes the static scan found that never once
 * appeared in the reactive manifest at all). It also prints the optional-argument, endpoint,
 * class-finding, unreached-cluster and dependency reports, the last applying ADR 0030's statuses to
 * each dependency the instances listed. The never-hit, class-finding and cluster reports apply
 * server ADR 0034 the way `YukonTestCollector` does. A constructor prints as `constructor(...)`
 * with its parameter types, as the server's web UI shows it.
 *
 * Takes the port to bind as its one argument, defaulting to [DemoPorts.COLLECTOR_PORT] for a run
 * by hand. Port 0 binds an ephemeral one; either way the port that was actually bound is printed,
 * so a caller that asked for 0 learns which one it got by reading this process's output.
 */
fun main(args: Array<String>) {
    val requestedPort = args.firstOrNull()?.toIntOrNull() ?: DemoPorts.COLLECTOR_PORT
    val server = startStubCollector(requestedPort)
    println("yukon stub collector listening on ${server.address.port}")

    Runtime.getRuntime().addShutdownHook(
        Thread {
            printNeverHitReport()
            printOmissionReport()
            printEndpointReport()
            printNeverLoadedReport()
            printClassFindingReport()
            printUnreachedClusterReport()
            printDependencyReport()
        },
    )
}

/**
 * Binds [port] and serves the three payload routes and `/__shutdown`. `internal` so a test can
 * post payloads to it without the shutdown-hook reports [main] adds.
 */
internal fun startStubCollector(port: Int): HttpServer {
    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/v1/yukon/deltas", ::handleDeltaBatch)
    server.createContext("/v1/yukon/manifest", ::handleManifest)
    server.createContext("/v1/yukon/static-baseline", ::handleStaticBaseline)
    server.createContext("/__shutdown", ::handleShutdown)
    server.start()
    return server
}

/**
 * The [Run] [resource] names, or null when its run id is empty. ADR 0032 has a consumer reject
 * such a payload, since nothing it carries can be kept apart from another run's data.
 */
private fun runOf(resource: ResourceAttributes): Run? =
    resource.runId.takeIf { it.isNotEmpty() }?.let { Run(resource.serviceInstanceId, it) }

/** Exits in-process, instead of relying on SIGTERM. SIGTERM can drop the shutdown-hook report mid-write. */
private fun handleShutdown(exchange: HttpExchange) {
    respondOk(exchange)
    Thread { System.exit(0) }.start()
}

private fun handleDeltaBatch(exchange: HttpExchange) {
    val batch = DeltaBatch.parseFrom(exchange.requestBody.readBytes())
    val run = runOf(batch.resource) ?: return respondBadRequest(exchange, "delta batch")
    allRuns += run
    for (delta in batch.deltasList) {
        val key = InstanceProbeKey(run, delta.classId, delta.probeIndex)
        everHit += key
        latestHitsTotal.merge(key, delta.hitsTotal, ::maxOf)
    }
    for (delta in batch.endpointDeltasList) {
        val key = InstanceEndpointKey(run, delta.endpointId)
        latestEndpointHitsTotal.merge(key, delta.hitsTotal, ::maxOf)
    }
    for (delta in batch.dependencyDeltasList) {
        val key = InstanceDependencyKey(run, delta.dependencyId)
        latestLoadedClassesTotal.merge(key, delta.loadedClassesTotal, ::maxOf)
        if (delta.firstLoadedAt > 0L) firstLoadedAt.merge(key, delta.firstLoadedAt, ::minOf)
    }
    if (batch.finalFlush) runsThatEndedCleanly += run
    val totalHits = latestHitsTotal.values.sum()
    val totalEndpointHits = latestEndpointHitsTotal.values.sum()
    val finalFlushSuffix = if (batch.finalFlush) " final=true" else ""
    println(
        "[flush] service=${batch.resource.serviceName} instance=${batch.resource.serviceInstanceId} " +
            "probes_with_activity=${batch.deltasList.size} total_hits=$totalHits " +
            "endpoints_with_activity=${batch.endpointDeltasList.size} total_endpoint_hits=$totalEndpointHits$finalFlushSuffix",
    )
    respondOk(exchange)
}

private fun handleManifest(exchange: HttpExchange) {
    val manifest = ProbeManifest.parseFrom(exchange.requestBody.readBytes())
    val run = runOf(manifest.resource) ?: return respondBadRequest(exchange, "manifest")
    for (location in manifest.probesList) {
        manifestProbes[InstanceProbeKey(run, location.classId, location.probeIndex)] =
            ProbeInfo(
                className = location.className,
                methodName = location.methodName,
                methodDescriptor = location.methodDescriptor,
                line = location.line,
                kind = location.kind,
                branchIndex = if (location.hasBranchIndex()) location.branchIndex else null,
                inline = location.inline,
                parameterIndex = if (location.hasParameterIndex()) location.parameterIndex else null,
                parameterName = location.parameterName.ifEmpty { null },
                overridable = location.overridable,
                targetClassName = location.targetClassName.ifEmpty { null },
                inlinedFromClassName = location.inlinedFromClassName.ifEmpty { null },
                generatedBy = location.generatedBy,
                siteIndex = if (location.hasSiteIndex()) location.siteIndex else null,
                static = location.static,
                lambdaBody = location.lambdaBody,
                parameterNames = location.parameterNamesList.toList(),
                genericSignature = location.genericSignature,
                extensionReceiver = location.extensionReceiver,
            )
        if (location.branchSitesList.isNotEmpty()) {
            manifestBranchSites[InstanceMethodKey(run, location.classId, location.methodName, location.methodDescriptor)] =
                location.branchSitesList.toList()
        }
        dynamicallyKnownClassNames += location.className
        if (location.callsList.isNotEmpty()) {
            manifestCallEdges[InstanceProbeKey(run, location.classId, location.probeIndex)] =
                location.callsList.map { callEdgeInfo(it) }
        }
        if (location.referencedClassesList.isNotEmpty()) {
            probeReferencedClasses[InstanceProbeKey(run, location.classId, location.probeIndex)] =
                location.referencedClassesList.toList()
        }
    }
    manifestRuns += run
    if (manifest.referencesRecorded) runsRecordingReferences += run
    if (manifest.dependenciesListed) runsWithDependenciesListed += run
    for (dependency in manifest.dependenciesList) {
        dependencyLocations[InstanceDependencyKey(run, dependency.dependencyId)] =
            DependencyView(
                dependencyId = dependency.dependencyId,
                identities = dependency.identitiesList.map { DependencyIdentityView(it.groupId, it.artifactId, it.version) },
                discoverySource = dependency.discoverySource,
                classCount = if (dependency.hasClassCount()) dependency.classCount else null,
                location = dependency.location,
            )
    }
    for (references in manifest.classReferencesList) {
        classLevelReferences[InstanceClassIdKey(run, references.classId)] = references.referencedClassesList.toList()
    }
    for (external in manifest.externalClassesList) {
        externalClasses[InstanceClassKey(run, external.className)] =
            ExternalClassView(if (external.hasDependencyId()) external.dependencyId else null, external.absent)
    }
    for (skipped in manifest.skippedClassesList) {
        skippedClasses[InstanceClassKey(run, skipped.className)] = SkippedInfo(skipped.reason, skipped.skippedAt)
        dynamicallyKnownClassNames += skipped.className
    }
    // A class's location record is committed with its probes, so this manifest names its class.
    val classNamesById = manifest.probesList.associate { it.classId to it.className }
    for (classLocation in manifest.classLocationsList) {
        supertypesByClassId[InstanceClassIdKey(run, classLocation.classId)] =
            SupertypesInfo(classLocation.superClassName.ifEmpty { null }, classLocation.interfaceNamesList)
        classNamesById[classLocation.classId]?.let { className ->
            classNamingByClassName[className] = ClassNaming(classLocation.sourceFile.ifEmpty { null }, classLocation.kotlinKind)
        }
    }
    for (endpoint in manifest.endpointsList) {
        manifestEndpoints[InstanceEndpointKey(run, endpoint.endpointId)] =
            EndpointInfo(
                verb = endpoint.verb,
                routeTemplate = endpoint.routeTemplate,
                verbatimTemplate = endpoint.verbatimTemplate,
                framework = endpoint.framework,
                discoverySource = endpoint.discoverySource,
                handlerClass = if (endpoint.hasHandlerClass()) endpoint.handlerClass else null,
                handlerMethod = if (endpoint.hasHandlerMethod()) endpoint.handlerMethod else null,
                handlerDescriptor = if (endpoint.hasHandlerDescriptor()) endpoint.handlerDescriptor else null,
            )
    }
    for (disabled in manifest.disabledEndpointModulesList) {
        disabledEndpointModules[InstanceModuleKey(run, disabled.module)] = disabled.reason
    }
    val callEdgeCount = manifest.probesList.sumOf { it.callsList.size }
    println(
        "[manifest] instance=${run.serviceInstanceId} received ${manifest.probesList.size} probe locations " +
            "(known total: ${manifestProbes.size}) and ${manifest.skippedClassesList.size} skipped classes " +
            "(known total: ${skippedClasses.size}), ${manifest.endpointsList.size} endpoints " +
            "(known total: ${manifestEndpoints.size}) and ${manifest.disabledEndpointModulesList.size} disabled endpoint modules, " +
            "$callEdgeCount call edges (known total: ${manifestCallEdges.values.sumOf { it.size }}) and " +
            "${manifest.classLocationsList.size} class location records (known total: ${supertypesByClassId.size}), " +
            "${manifest.dependenciesList.size} dependencies (known total: ${dependencyLocations.size}) and " +
            "${manifest.externalClassesList.size} external classes (known total: ${externalClasses.size}), " +
            "dependencies_listed=${manifest.dependenciesListed}",
    )
    respondOk(exchange)
}

private fun handleStaticBaseline(exchange: HttpExchange) {
    val baseline = StaticBaseline.parseFrom(exchange.requestBody.readBytes())
    val run = runOf(baseline.resource) ?: return respondBadRequest(exchange, "static baseline")
    for (declaredClass in baseline.declaredClassesList) {
        staticallyDeclaredClasses[declaredClass.className] =
            declaredClass.methodsList.map {
                DeclaredMethodInfo(
                    it.methodName,
                    it.methodDescriptor,
                    it.inline,
                    it.callsList.map(::callEdgeInfo),
                    it.generatedBy,
                    it.referencedClassesList.toList(),
                    it.static,
                    it.parameterNamesList.toList(),
                    it.genericSignature,
                    it.extensionReceiver,
                )
            }
        baselineReferences[InstanceClassKey(run, declaredClass.className)] =
            BaselineReferences(declaredClass.referencedClassesList.toList(), staticallyDeclaredClasses.getValue(declaredClass.className))
        staticallyDeclaredSupertypes[declaredClass.className] =
            SupertypesInfo(declaredClass.superClassName.ifEmpty { null }, declaredClass.interfaceNamesList)
        classNamingByClassName.putIfAbsent(
            declaredClass.className,
            ClassNaming(declaredClass.sourceFile.ifEmpty { null }, declaredClass.kotlinKind),
        )
    }
    for (external in baseline.externalClassesList) {
        externalClasses[InstanceClassKey(run, external.className)] =
            ExternalClassView(if (external.hasDependencyId()) external.dependencyId else null, external.absent)
    }
    for (unsafe in baseline.staticallyUnsafeClassesList) {
        staticallyUnsafeClasses[unsafe.className] = unsafe.reason
    }
    for (unreadable in baseline.unreadableClassesList) {
        staticallyUnreadableClasses[unreadable.className] = unreadable.reason
    }
    for (unprobed in baseline.unprobedClassesList) {
        staticallyUnprobedClasses[unprobed.className] = unprobed.reason
    }
    val progress =
        scans.computeIfAbsent(ScanKey(run, baseline.scannedAt)) { ScanProgress(baseline.chunkCount) }
    progress.received += baseline.chunkIndex
    val callEdgeCount = baseline.declaredClassesList.sumOf { c -> c.methodsList.sumOf { it.callsList.size } }
    println(
        "[static-baseline] service=${baseline.resource.serviceName} chunk=${baseline.chunkIndex + 1}/${baseline.chunkCount} " +
            "declared_classes=${baseline.declaredClassesList.size} " +
            "statically_unsafe=${baseline.staticallyUnsafeClassesList.size} unreadable=${baseline.unreadableClassesList.size} " +
            "unprobed=${baseline.unprobedClassesList.size} call_edges=$callEdgeCount",
    )
    respondOk(exchange)
}

private fun callEdgeInfo(edge: CallEdge): CallEdgeInfo =
    CallEdgeInfo(
        edge.className,
        edge.methodName,
        edge.methodDescriptor,
        edge.virtual,
        if (edge.hasGuard()) edge.guard else null,
        edge.kind == CallEdgeKind.CREATES,
        edge.implementedInterface.ifEmpty { null },
    )

private fun respondOk(exchange: HttpExchange) {
    exchange.sendResponseHeaders(200, -1)
    exchange.close()
}

/** Answers 400 to a [payload] whose resource has no run id, and keeps nothing from it. See ADR 0032. */
private fun respondBadRequest(
    exchange: HttpExchange,
    payload: String,
) {
    val message = "rejected $payload: resource.run_id is empty"
    println("[rejected] $message")
    val body = message.toByteArray()
    exchange.sendResponseHeaders(400, body.size.toLong())
    exchange.responseBody.use { it.write(body) }
}

/**
 * Prints every judgeable probe with no hits that is a never-hit row under server ADR 0034. A
 * `<clinit>` is a class state and never a row. A constructor is a row only as an unused overload,
 * when another constructor of its class ran. A method a class finding covers, and a branch in one,
 * is not a row: [printClassFindingReport] reports the class instead. A lambda body that folds into
 * never-hit methods listed here is not a row either, and neither is a branch in it. The report
 * counts each kind of folded probe apart.
 */
private fun printNeverHitReport() {
    // An optional-argument probe reading zero means its parameter is never omitted, which is
    // ALWAYS SUPPLIED, not dead code, so it is excluded here entirely and reported by
    // printOmissionReport instead. See ADR 0021.
    val judgeableKeys = manifestProbes.keys.filter { manifestProbes[it]?.kind != ProbeKind.OPTIONAL_ARGUMENT }
    val neverHitKeys = judgeableKeys.filter { it !in everHit }
    val (inlineNeverHit, notInlineNeverHit) = neverHitKeys.partition { manifestProbes[it]?.inline == true }
    // A generated probe, such as a data class's copy or an enum's values, is kept and counted,
    // but the compiler will emit it again regardless of what the adopter does, so a zero hit
    // total is not a finding the adopter can act on. See ADR 0026.
    val (generatedNeverHit, judgeableNeverHit) =
        notInlineNeverHit.partition { manifestProbes[it]?.generatedBy != GeneratedBy.GENERATED_BY_NONE }
    val judgement = judgeClasses()

    fun methodOf(key: InstanceProbeKey) = manifestProbes.getValue(key).let { NodeKey(it.className, it.methodName, it.methodDescriptor) }
    val (inClassFindings, notInClassFindings) = judgeableNeverHit.partition { methodOf(it) in judgement.coveredMethods }
    val (inNeverHitCode, notFolded) = notInClassFindings.partition { methodOf(it) in judgement.inNeverHitCode }
    val (judgeable, classStates) =
        notFolded.partition { key ->
            val info = manifestProbes.getValue(key)
            info.kind != ProbeKind.METHOD ||
                when (info.methodName) {
                    CLASS_INIT -> false
                    CONSTRUCTOR -> info.className in judgement.constructed
                    else -> true
                }
        }
    val judgeableTotal =
        judgeableKeys.count { key ->
            val probe = manifestProbes[key]
            probe != null && !probe.inline && probe.generatedBy == GeneratedBy.GENERATED_BY_NONE
        }
    println()
    println("=== yukon demo: dead code report ===")
    println("runs that sent a final flush: ${runsThatEndedCleanly.size} of ${allRuns.size}")
    println("known probes: ${manifestProbes.size}, ever hit: ${everHit.size}, never hit: ${judgeable.size}")
    if (judgeableTotal > 0) {
        println("dead: %.1f%%".format(100.0 * judgeable.size / judgeableTotal))
    }
    judgeable
        .mapNotNull { key -> manifestProbes[key]?.let { key to it } }
        .sortedWith(compareBy({ it.second.className }, { it.second.methodName }, { it.second.line }, { it.second.branchIndex ?: -1 }))
        .forEach { (key, info) ->
            val inlinedFromSuffix = info.inlinedFromClassName?.let { " (inlined from $it)" } ?: ""
            val where = "(instance ${key.run.serviceInstanceId}, class ${key.classId}, probe ${key.probeIndex})"
            val branchIndex = info.branchIndex
            if (branchIndex == null) {
                val method = methodText(info.className, info.methodName, info.methodDescriptor, info.line)
                val kind = if (info.methodName == CONSTRUCTOR) "CONSTRUCTOR, unused overload" else "${info.kind}"
                println("  NEVER HIT: $method [$kind]$inlinedFromSuffix $where")
            } else {
                val site =
                    info.siteIndex?.let { siteIndex ->
                        manifestBranchSites[InstanceMethodKey(key.run, key.classId, info.methodName, info.methodDescriptor)]
                            ?.firstOrNull { it.siteIndex == siteIndex }
                    }
                val description = site?.let { describeNeverHitOutcome(it, branchIndex) } ?: "branch at line ${info.line}"
                val method = methodText(info.className, info.methodName, info.methodDescriptor, info.line)
                println("  NEVER HIT: $method $description$inlinedFromSuffix $where [${info.kind} branch#$branchIndex]")
            }
        }
    // Kotlin inline functions copy their body into the caller, so their own probe reads near
    // zero however often they run: no "never hit" claim is made about them. See ADR 0022.
    println("inline (not judged): ${inlineNeverHit.size}")
    println("generated (not judged): ${generatedNeverHit.size}")
    println("in class findings (reported by class): ${inClassFindings.size}")
    println("in never-hit methods (lambda bodies reported with their creator): ${inNeverHitCode.size}")
    println("static initialisers and lone constructors (not listed): ${classStates.size}")
    if (skippedClasses.isNotEmpty()) {
        println("skipped (matched but could not be instrumented): ${skippedClasses.size}")
        skippedClasses.entries
            .sortedWith(compareBy({ it.key.run.serviceInstanceId }, { it.key.className }))
            .forEach { (key, info) -> println("  SKIPPED: ${key.className} (instance ${key.run.serviceInstanceId}) - ${info.reason}") }
    }
    println("=====================================")
}

/**
 * The source file a report names [className] by: set for a file facade or a multi-file part that
 * has one, and null for every other class. See ADR 0041.
 */
private fun sourceFileNaming(className: String): String? {
    val naming = classNamingByClassName[className] ?: return null
    val isFileKind = naming.kotlinKind == KotlinKind.FILE_FACADE || naming.kotlinKind == KotlinKind.MULTIFILE_CLASS_PART
    return naming.sourceFile.takeIf { isFileKind }
}

/** Whether [className] is a multi-file facade, which a report tags. See ADR 0041. */
private fun isMultifileFacade(className: String): Boolean =
    classNamingByClassName[className]?.kotlinKind == KotlinKind.MULTIFILE_CLASS_FACADE

/**
 * A class as the reports print it (ADR 0041). A file facade or a multi-file part prints as its
 * source file, such as `DemoServerMain.kt`. A multi-file facade prints by its JVM name with a
 * `(multi-file facade)` tag. Any other class, or a class whose kind no payload named, prints by
 * its JVM name.
 */
internal fun classText(className: String): String =
    sourceFileNaming(className) ?: if (isMultifileFacade(className)) "$className (multi-file facade)" else className

/**
 * A method as the reports print it: `Class#name`, or `Class#constructor(int, String)` for a
 * constructor. A member of a file facade or a multi-file part prints as a top-level function with
 * its file, such as `handleCheckout (DemoServerMain.kt)`, and [line], when given, joins the file:
 * `handleCheckout (DemoServerMain.kt:121)`. A member of a multi-file facade carries the facade's
 * tag. See ADR 0041.
 */
internal fun methodText(
    className: String,
    methodName: String,
    methodDescriptor: String,
    line: Int? = null,
): String {
    val shown = if (methodName == CONSTRUCTOR) constructorText(methodDescriptor) else methodName
    val lineSuffix = line?.let { ":$it" } ?: ""
    sourceFileNaming(className)?.let { return "$shown ($it$lineSuffix)" }
    val tag = if (isMultifileFacade(className)) " (multi-file facade)" else ""
    return "$className#$shown$lineSuffix$tag"
}

/** A constructor's name as source reads it: `constructor(...)` with its parameters' simple type names. */
private fun constructorText(methodDescriptor: String): String {
    val types = mutableListOf<String>()
    var i = methodDescriptor.indexOf('(') + 1
    while (i in 1 until methodDescriptor.length && methodDescriptor[i] != ')') {
        var dimensions = 0
        while (methodDescriptor[i] == '[') {
            dimensions++
            i++
        }
        val type =
            if (methodDescriptor[i] == 'L') {
                val end = methodDescriptor.indexOf(';', i)
                methodDescriptor.substring(i + 1, end).substringAfterLast('/').also { i = end }
            } else {
                PRIMITIVE_NAMES[methodDescriptor[i]] ?: methodDescriptor[i].toString()
            }
        types += type + "[]".repeat(dimensions)
        i++
    }
    return "constructor(${types.joinToString(", ")})"
}

private val PRIMITIVE_NAMES =
    mapOf('Z' to "boolean", 'B' to "byte", 'C' to "char", 'S' to "short", 'I' to "int", 'J' to "long", 'F' to "float", 'D' to "double")

/**
 * Applies server ADR 0034's class rules to every loaded class, over its judgeable METHOD probes
 * merged across runs with hits summed. A class with such a probe loaded, so none of these is never
 * loaded.
 *
 * A class is never initialised when it has a `<clinit>` and that never ran. Otherwise it is never
 * instantiated when it has a `<init>`, none ran, and it has a method that is neither static nor a
 * constructor. A never-initialised class covers every never-hit method. A never-instantiated class
 * covers each never-hit constructor and each never-hit method that is not static. A never-hit
 * lambda body then folds with its creators; see [foldLambdaBodies]. Same rules as
 * `YukonTestCollector.judgeClasses`.
 */
private fun judgeClasses(): ClassJudgement {
    val methods =
        manifestProbes.entries
            .filter { (_, probe) -> probe.kind == ProbeKind.METHOD && !probe.inline && probe.generatedBy == GeneratedBy.GENERATED_BY_NONE }
            .groupBy { (_, probe) -> NodeKey(probe.className, probe.methodName, probe.methodDescriptor) }
            .mapValues { (_, entries) ->
                MethodFacts(
                    entries.sumOf { (key, _) -> latestHitsTotal[key] ?: 0L },
                    entries.any { it.value.static },
                    entries.any { it.value.lambdaBody },
                )
            }
    val findings = mutableMapOf<String, ClassFinding>()
    val covered = mutableMapOf<String, List<NodeKey>>()
    val constructed = mutableSetOf<String>()
    for ((className, classMethods) in methods.entries.groupBy { it.key.className }) {
        val initialisers = classMethods.filter { it.key.methodName == CLASS_INIT }
        val constructors = classMethods.filter { it.key.methodName == CONSTRUCTOR }
        if (constructors.any { it.value.hits > 0L }) constructed += className
        val finding =
            when {
                initialisers.isNotEmpty() && initialisers.all { it.value.hits == 0L } -> {
                    ClassFinding.NEVER_INITIALISED
                }

                constructors.isNotEmpty() &&
                    className !in constructed &&
                    classMethods.any { it.key.methodName != CONSTRUCTOR && it.key.methodName != CLASS_INIT && !it.value.static } -> {
                    ClassFinding.NEVER_INSTANTIATED
                }

                else -> {
                    continue
                }
            }
        findings[className] = finding
        covered[className] =
            classMethods
                .filter { (key, facts) ->
                    facts.hits == 0L &&
                        (finding == ClassFinding.NEVER_INITIALISED || key.methodName == CONSTRUCTOR || (key.methodName != CLASS_INIT && !facts.static))
                }.map { it.key }
    }
    val (intoClassFindings, inNeverHitCode) = foldLambdaBodies(methods, findings, covered.values.flatten().toSet(), constructed)
    for (lambda in intoClassFindings) covered[lambda.className] = covered.getValue(lambda.className) + lambda
    return ClassJudgement(findings, covered, inNeverHitCode, constructed)
}

/**
 * Every method some CREATES call edge names, with the methods whose edges name it: its creators.
 * Reads the edges of every METHOD probe and of every static-baseline declaration. A method never
 * counts as its own creator. See ADR 0028.
 */
private fun creatorsOf(): Map<NodeKey, Set<NodeKey>> {
    val creators = mutableMapOf<NodeKey, MutableSet<NodeKey>>()

    fun add(
        creator: NodeKey,
        edges: List<CallEdgeInfo>,
    ) {
        for (edge in edges) {
            if (!edge.creates) continue
            val created = NodeKey(edge.className, edge.methodName, edge.methodDescriptor)
            if (created != creator) creators.getOrPut(created) { mutableSetOf() } += creator
        }
    }
    for ((key, edges) in manifestCallEdges) {
        val info = manifestProbes[key] ?: continue
        add(NodeKey(info.className, info.methodName, info.methodDescriptor), edges)
    }
    for ((className, declared) in staticallyDeclaredClasses) {
        declared.forEach { add(NodeKey(className, it.methodName, it.methodDescriptor), it.calls) }
    }
    return creators
}

/**
 * Folds each judgeable never-hit lambda body whose every creator is judgeable and never hit, and is
 * itself covered by a class finding or a row of the never-hit report: a method other than
 * `<clinit>` and a lone constructor. A lambda body some method created is one or the other, so
 * nested lambda bodies fold with the outermost one. A lambda body with no known creator, or with a
 * creator that ran, never folds. See server ADR 0034.
 *
 * Returns the folded lambda bodies in two sets. The first fold into their own class's finding:
 * every creator is covered by a class finding or is in that first set. The rest fold into never-hit
 * methods that are rows of their own.
 */
private fun foldLambdaBodies(
    methods: Map<NodeKey, MethodFacts>,
    findings: Map<String, ClassFinding>,
    covered: Set<NodeKey>,
    constructed: Set<String>,
): Pair<Set<NodeKey>, Set<NodeKey>> {
    val creators = creatorsOf()

    fun absorbs(creator: NodeKey): Boolean {
        if ((methods[creator] ?: return false).hits > 0L) return false
        if (creator in covered) return true
        return when (creator.methodName) {
            CLASS_INIT -> false
            CONSTRUCTOR -> creator.className in constructed
            else -> true
        }
    }
    val folded =
        methods
            .filter { (key, facts) -> facts.lambdaBody && facts.hits == 0L && key !in covered }
            .keys
            .filter { lambda -> creators[lambda].orEmpty().let { it.isNotEmpty() && it.all(::absorbs) } }
            .toSet()
    val intoClassFindings = mutableSetOf<NodeKey>()
    var changed = true
    while (changed) {
        changed = false
        for (lambda in folded - intoClassFindings) {
            if (lambda.className in findings && creators.getValue(lambda).all { it in covered || it in intoClassFindings }) {
                intoClassFindings += lambda
                changed = true
            }
        }
    }
    return intoClassFindings to (folded - intoClassFindings)
}

/**
 * Reports each class loaded and never initialised, and each class loaded and never instantiated,
 * as [judgeClasses] finds them. A class's methods are its METHOD probes other than `<clinit>`, one
 * entry per name, inline and generated ones included. Instances loading counts the instances that
 * sent a probe for the class. See server ADR 0034.
 */
private fun printClassFindingReport() {
    println()
    println("=== yukon demo: class findings ===")
    val findings = judgeClasses().findings
    println(
        "never initialised: ${findings.values.count { it == ClassFinding.NEVER_INITIALISED }}, " +
            "never instantiated: ${findings.values.count { it == ClassFinding.NEVER_INSTANTIATED }}",
    )
    for (finding in listOf(ClassFinding.NEVER_INITIALISED, ClassFinding.NEVER_INSTANTIATED)) {
        findings
            .filterValues { it == finding }
            .keys
            .sorted()
            .forEach { className ->
                val probes = manifestProbes.filter { (_, info) -> info.className == className }
                val methods =
                    probes.values
                        .filter { it.kind == ProbeKind.METHOD && it.methodName != CLASS_INIT }
                        .map { if (it.methodName == CONSTRUCTOR) "constructor" else it.methodName }
                        .distinct()
                        .sorted()
                val instances = probes.keys.map { it.run.serviceInstanceId }.distinct().size
                println(
                    "  ${finding.text.uppercase()}: ${classText(className)} (methods: ${methods.joinToString(", ")}) " +
                        "(instances loading: $instances)",
                )
            }
    }
    println("==================================")
}

/**
 * A never-hit outcome as a person reads it: its site's condition, the result that never happened,
 * and the code that runs only through the outcome. A site with no condition is named by its line.
 * A case of a switch read back to its source cases is named by its label. See ADRs 0037 and 0038.
 */
private fun describeNeverHitOutcome(
    site: BranchSite,
    branchIndex: Int,
): String? {
    val outcome = site.outcomesList.firstOrNull { it.branchIndex == branchIndex } ?: return null
    val condition = site.conditionList.takeIf { it.isNotEmpty() }?.let { "`${renderCondition(it)}`" }
    val subject = condition ?: "the branch at line ${site.line}"
    val result =
        when (outcome.role) {
            BranchRole.FALL_THROUGH -> {
                if (condition != null) "$condition was never true" else "$subject never fell through"
            }

            BranchRole.TAKEN -> {
                if (condition != null) "$condition was never false" else "$subject never jumped"
            }

            BranchRole.CASE -> {
                when {
                    outcome.caseLabelCount > 0 -> "$subject was never `${renderCondition(outcome.caseLabelList)}`"
                    outcome.hasCaseKey() -> "$subject was never ${outcome.caseKey}"
                    else -> "a case of $subject never ran"
                }
            }

            BranchRole.DEFAULT -> {
                "$subject never reached its default"
            }

            else -> {
                "$subject had an outcome that never ran"
            }
        }
    val guarded =
        when {
            outcome.guardedLinesList.isNotEmpty() && outcome.partlyGuardedLinesList.isNotEmpty() -> {
                "only path to ${renderRanges(outcome.guardedLinesList)}, partly to ${renderRanges(outcome.partlyGuardedLinesList)}"
            }

            outcome.guardedLinesList.isNotEmpty() -> {
                "only path to ${renderRanges(outcome.guardedLinesList)}"
            }

            outcome.partlyGuardedLinesList.isNotEmpty() -> {
                "partly the path to ${renderRanges(outcome.partlyGuardedLinesList)}"
            }

            else -> {
                "guards no code of its own"
            }
        }
    return "$result, $guarded"
}

/** A condition as source text: code as it is, a string literal quoted and escaped, and a placeholder as `…`. */
private fun renderCondition(parts: List<ConditionPart>): String =
    parts.joinToString("") { part ->
        when (part.kind) {
            ConditionPartKind.STRING_LITERAL -> quote(part.text)
            ConditionPartKind.PLACEHOLDER -> "…"
            else -> part.text
        }
    }

private fun quote(value: String): String =
    buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

private fun renderRanges(ranges: List<LineRange>): String =
    ranges.joinToString(", ") { range ->
        val lines = if (range.firstLine == range.lastLine) "${range.firstLine}" else "${range.firstLine}-${range.lastLine}"
        if (range.sourceFile.isEmpty()) "line $lines" else "${range.sourceFile}:$lines"
    }

/**
 * Reports every optional parameter found never supplied (every caller took the default, so the
 * parameter can go) or always supplied (the default value is dead). Every omission probe naming
 * the same parameter is summed before either rule is judged: a Scala constructor default carries
 * two, a module getter resolved across the class boundary and that class's own static forwarder
 * resolved in class, and judging them apart can call one never supplied while the other reads as
 * always supplied for the same parameter. See ADR 0023. Both rules require the target's own
 * summed hit total to be above zero, and skip a target with no method probe at all (an abstract
 * interface method) or an inline target, the same reasons [printNeverHitReport] excludes those.
 * "Never supplied" is claimed only for a non-overridable target, since an overridable target's
 * omissions are spread across whichever override actually ran. See ADR 0021.
 */
private fun printOmissionReport() {
    println()
    println("=== yukon demo: optional argument report ===")
    val omissionGroups =
        manifestProbes.entries
            .filter { (_, info) ->
                info.kind == ProbeKind.OPTIONAL_ARGUMENT && !info.inline && info.generatedBy == GeneratedBy.GENERATED_BY_NONE
            }.groupBy { (key, info) ->
                OmissionTargetKey(
                    key.run,
                    info.targetClassName ?: info.className,
                    info.methodName,
                    info.methodDescriptor,
                    info.parameterIndex,
                )
            }
    val neverSupplied = mutableListOf<Pair<OmissionTargetKey, ProbeInfo>>()
    val alwaysSupplied = mutableListOf<Pair<OmissionTargetKey, ProbeInfo>>()
    for ((groupKey, members) in omissionGroups) {
        val targetHits =
            manifestProbes.entries
                .filter { (targetKey, targetInfo) ->
                    targetKey.run == groupKey.run &&
                        targetInfo.kind == ProbeKind.METHOD &&
                        targetInfo.className == groupKey.targetClassName &&
                        targetInfo.methodName == groupKey.methodName &&
                        targetInfo.methodDescriptor == groupKey.methodDescriptor
                }.sumOf { (targetKey, _) -> latestHitsTotal[targetKey] ?: 0L }
        if (targetHits <= 0L) continue
        val omitted = members.sumOf { (key, _) -> latestHitsTotal[key] ?: 0L }
        val representative = members.first().value
        if (!representative.overridable && omitted == targetHits) neverSupplied += groupKey to representative
        if (omitted == 0L) alwaysSupplied += groupKey to representative
    }
    println("never supplied: ${neverSupplied.size}, always supplied: ${alwaysSupplied.size}")
    neverSupplied
        .sortedWith(compareBy({ it.first.targetClassName }, { it.first.methodName }, { it.first.parameterIndex }))
        .forEach { (groupKey, info) ->
            println(
                "  NEVER SUPPLIED: ${groupKey.targetClassName}#${groupKey.methodName}(${info.parameterName}) " +
                    "(instance ${groupKey.run.serviceInstanceId})",
            )
        }
    alwaysSupplied
        .sortedWith(compareBy({ it.first.targetClassName }, { it.first.methodName }, { it.first.parameterIndex }))
        .forEach { (groupKey, info) ->
            println(
                "  ALWAYS SUPPLIED: ${groupKey.targetClassName}#${groupKey.methodName}(${info.parameterName}) " +
                    "(instance ${groupKey.run.serviceInstanceId})",
            )
        }
    println("==============================================")
}

/**
 * Reports every declared endpoint, called or not. An endpoint's own hit count, not the method
 * tier's, is what says "called": a handler can back an endpoint the method tier never sees on its
 * own (a lambda invoked through a hidden class, a handler in an excluded package), so this report
 * is the only one able to say `/promo` was never called.
 */
private fun printEndpointReport() {
    println()
    println("=== yukon demo: endpoint report ===")
    val calledCount = manifestEndpoints.count { (key, _) -> (latestEndpointHitsTotal[key] ?: 0L) > 0L }
    println("known endpoints: ${manifestEndpoints.size}, called: $calledCount, never called: ${manifestEndpoints.size - calledCount}")
    manifestEndpoints.entries
        .sortedWith(compareBy({ it.value.routeTemplate }, { it.value.verb }))
        .forEach { (key, info) ->
            val hits = latestEndpointHitsTotal[key] ?: 0L
            val handlerSuffix =
                info.handlerClass?.let { cls -> " handler=$cls${info.handlerMethod?.let { "#$it" } ?: ""}" } ?: ""
            val tag = "[${info.framework}, ${info.discoverySource}]"
            if (hits > 0L) {
                println("  CALLED: ${info.verb} ${info.routeTemplate} calls=$hits $tag$handlerSuffix")
            } else {
                println("  NEVER CALLED: ${info.verb} ${info.routeTemplate} $tag$handlerSuffix")
            }
        }
    if (disabledEndpointModules.isNotEmpty()) {
        println("disabled endpoint modules: ${disabledEndpointModules.size}")
        disabledEndpointModules.entries
            .sortedWith(compareBy({ it.key.run.serviceInstanceId }, { it.key.module }))
            .forEach { (key, reason) -> println("  DISABLED: ${key.module} (instance ${key.run.serviceInstanceId}) - $reason") }
    }
    println("====================================")
}

/**
 * A class is "never loaded" only if the static scan declared it AND the reactive manifest never
 * once mentioned it, by name, for any reason - not even as a skipped class. A class already
 * counted as statically unsafe, unreadable, or unprobed is excluded: the static scanner could not
 * classify it as probe-eligible either way, so it is reported under its own heading instead.
 *
 * The diff only runs once every chunk of every scan has arrived. A partial scan can say
 * "declared" for the classes it carries, but never "never loaded" for the ones it is missing.
 */
private fun printNeverLoadedReport() {
    println()
    println("=== yukon demo: never-loaded report (static baseline) ===")
    val incomplete = scans.filterValues { !it.complete }
    if (incomplete.isNotEmpty()) {
        incomplete.forEach { (key, progress) ->
            println(
                "  INCOMPLETE SCAN: instance ${key.run.serviceInstanceId} scanned_at ${key.scannedAt} received " +
                    "${progress.received.size} of ${progress.chunkCount} chunks; not diffing",
            )
        }
        println("===========================================================")
        return
    }
    val neverLoadedAll = staticallyDeclaredClasses.filterKeys { it !in dynamicallyKnownClassNames }
    val (allInlineOrGenerated, neverLoaded) =
        neverLoadedAll.entries.partition { (_, methods) ->
            methods.isNotEmpty() && methods.all { it.inline || it.generatedBy != GeneratedBy.GENERATED_BY_NONE }
        }
    println(
        "statically declared: ${staticallyDeclaredClasses.size}, confirmed loaded: " +
            "${staticallyDeclaredClasses.keys.count { it in dynamicallyKnownClassNames }}, never loaded: ${neverLoaded.size}",
    )
    neverLoaded
        .sortedBy { it.key }
        .forEach { (className, methods) ->
            val methodNames = methods.joinToString(", ") { it.methodName }
            println("  NEVER LOADED: ${classText(className)} (methods: $methodNames)")
        }
    // A class made only of inline functions is never loaded by a Kotlin caller at all, and the
    // compiler emits a generated method again regardless of what the adopter does, so a class
    // whose every method is one or the other never loading is not evidence it is dead. See ADR
    // 0022 and ADR 0026.
    if (allInlineOrGenerated.isNotEmpty()) {
        println("all inline or generated (not judged): ${allInlineOrGenerated.size}")
        allInlineOrGenerated.sortedBy { it.key }.forEach { (className, _) -> println("  ALL INLINE OR GENERATED: ${classText(className)}") }
    }
    if (staticallyUnprobedClasses.isNotEmpty()) {
        println("nothing to probe (in scope, but no concrete methods): ${staticallyUnprobedClasses.size}")
        staticallyUnprobedClasses.entries
            .sortedBy { it.key }
            .forEach { (className, reason) -> println("  UNPROBED: $className - $reason") }
    }
    if (staticallyUnsafeClasses.isNotEmpty()) {
        println("statically unsafe (would be skipped if it ever loaded): ${staticallyUnsafeClasses.size}")
        staticallyUnsafeClasses.entries
            .sortedBy { it.key }
            .forEach { (className, reason) -> println("  STATICALLY UNSAFE: $className - $reason") }
    }
    if (staticallyUnreadableClasses.isNotEmpty()) {
        println("statically unreadable (class file found but could not be parsed): ${staticallyUnreadableClasses.size}")
        staticallyUnreadableClasses.entries
            .sortedBy { it.key }
            .forEach { (className, reason) -> println("  UNREADABLE: $className - $reason") }
    }
    println("===========================================================")
}

/** Orders a [ClusterMember] the way [printNeverHitReport] orders a probe: by class, then method, then descriptor. */
private val clusterMemberComparator: Comparator<ClusterMember> = compareBy({ it.className }, { it.methodName }, { it.methodDescriptor })

/**
 * Reports every unreached cluster: a root plus every never-hit method reachable from it whose
 * every in-scope caller is itself already in the cluster. Applies the same rule
 * `YukonTestCollector.unreachedClusters` applies within a test JVM, over the manifest call edges,
 * class supertypes, and complete-baseline declarations this stub already stores. An untaken
 * outcome root prints as [printNeverHitReport] prints its outcome, then the method that holds it.
 * A root reached from hit names the methods with hits that call it, and so does a class root that
 * one calls. A class the cluster holds whole prints once, with its finding and method count, in
 * place of its methods. See ADRs 0024 and 0039, server ADR 0034 and CONTEXT.md, "Unreached
 * cluster".
 */
private fun printUnreachedClusterReport() {
    println()
    println("=== yukon demo: unreached clusters ===")
    val clusters = computeUnreachedClusters()
    println("clusters: ${clusters.size}")
    val routesByHandler = routesByHandler()
    clusters.forEach { cluster ->
        val method = methodText(cluster.root.className, cluster.root.methodName, cluster.root.methodDescriptor)
        val outcome = cluster.rootOutcome
        val callers = cluster.reachedFrom.joinToString(", ") { methodText(it.className, it.methodName, it.methodDescriptor) }
        val root =
            when (cluster.rootKind) {
                ClusterRootKind.UNTAKEN_OUTCOME -> {
                    val description =
                        outcome?.site?.let { describeNeverHitOutcome(it, outcome.branchIndex) } ?: "branch#${outcome?.branchIndex} never ran"
                    val at = methodText(cluster.root.className, cluster.root.methodName, cluster.root.methodDescriptor, outcome?.line)
                    "$description, in $at (untaken outcome)"
                }

                ClusterRootKind.REACHED_FROM_HIT -> {
                    "$method (reached from hit, called from $callers)"
                }

                ClusterRootKind.UNCALLED -> {
                    "$method (uncalled)"
                }

                ClusterRootKind.CLASS_FINDING -> {
                    val calledFrom = if (callers.isEmpty()) "" else ", called from $callers"
                    "${classText(cluster.root.className)} (class finding: ${cluster.rootFinding?.text}$calledFrom)"
                }
            }
        val routes = routesByHandler[NodeKey(cluster.root.className, cluster.root.methodName, cluster.root.methodDescriptor)]
        val routesSuffix = routes?.let { " routes=${it.joinToString(", ", "[", "]")}" } ?: ""
        println(
            "UNREACHED CLUSTER: root $root, ${cluster.membersTotal} methods, ${cluster.neverLoadedClasses} never-loaded classes$routesSuffix",
        )
        cluster.wholeClasses.forEach { whole ->
            val finding = whole.finding?.let { ", ${it.text}" } ?: ""
            println("  ${classText(whole.className)} (whole class$finding, ${whole.methodsTotal} methods)")
        }
        cluster.members.forEach { member ->
            val suffix = if (member.neverLoaded) " (never loaded)" else ""
            println("  ${methodText(member.className, member.methodName, member.methodDescriptor)}$suffix")
        }
    }
    println("=======================================")
}

/**
 * The endpoints whose handler join names each method, as `verb template` strings, merged across
 * instances the way the call graph itself is. This is the stub's version of the `routes` the real
 * server puts on a never-hit row and on an unreached cluster's root: a never-called endpoint and
 * the never-hit method behind it are one finding, and printing the route on the root makes that
 * visible without a second lookup. A handler in a hidden class has no join and so no entry here.
 */
private fun routesByHandler(): Map<NodeKey, List<String>> =
    manifestEndpoints.values
        .filter { it.handlerClass != null && it.handlerMethod != null && it.handlerDescriptor != null }
        .groupBy(
            { NodeKey(it.handlerClass!!, it.handlerMethod!!, it.handlerDescriptor!!) },
            { "${it.verb} ${it.routeTemplate}" },
        ).mapValues { (_, routes) -> routes.distinct().sorted() }

/**
 * Every unreached cluster in the call graph, sorted by member count descending, then by root.
 *
 * The graph holds method nodes, outcome nodes and class nodes. An outcome node is a judgeable
 * BRANCH probe with no hits, merged across runs by its method and branch index, in a method with
 * hits. A class node is a class that holds a class finding, standing for the never-hit methods the
 * finding covers, which are never method nodes of their own; a never-loaded class stands for every
 * method node it has. A call edge whose guard names an outcome node counts as a call from that
 * outcome node, and any other edge as a call from its method or the class node standing for it.
 * An edge into a covered method goes into its class node. An outcome node has one caller: the
 * outcome node its site's guard names, or else its method. This stub hears from one build of the
 * demo, so a guard's branch index names one outcome of the caller's class and is looked up there
 * directly.
 *
 * A root is a never-hit node with no caller or with a caller that is a method with hits. A method
 * root is [ClusterRootKind.UNCALLED] or [ClusterRootKind.REACHED_FROM_HIT], an outcome root
 * [ClusterRootKind.UNTAKEN_OUTCOME], and a class root [ClusterRootKind.CLASS_FINDING]. A `<clinit>`
 * is never a root. An outcome or class root whose cluster holds nothing but itself and outcome
 * nodes gives no cluster. See ADR 0039 and server ADR 0034.
 */
private fun computeUnreachedClusters(): List<UnreachedClusterInfo> {
    val graph = computeCallGraph()

    fun isHit(key: NodeKey) = (graph.nodes[key]?.hits ?: 0L) > 0L

    val classNodes = buildClassNodes(graph)
    val coveredBy = classNodes.flatMap { (node, info) -> info.methods.map { it to node } }.toMap()
    val outcomes = buildOutcomeNodes(::isHit)
    val clusterGraph = buildClusterGraph(graph, outcomes, coveredBy)

    fun isNeverHit(node: ClusterNode) =
        when {
            node.isClass -> node in classNodes
            node.branchIndex != null -> node in outcomes
            else -> !isHit(node.method) && node.method !in coveredBy
        }

    val neverHitNodes =
        graph.nodes.keys
            .filter { !isHit(it) && it !in coveredBy && it.methodName != CLASS_INIT }
            .map { ClusterNode(it) } + outcomes.keys + classNodes.keys
    return neverHitNodes
        .mapNotNull { node ->
            val callers = clusterGraph.callersOf[node].orEmpty()
            val hitCallers = callers.filter { it.branchIndex == null && !it.isClass && isHit(it.method) }
            if (callers.isNotEmpty() && hitCallers.isEmpty()) return@mapNotNull null
            val kind =
                when {
                    node.isClass -> ClusterRootKind.CLASS_FINDING
                    node.branchIndex != null -> ClusterRootKind.UNTAKEN_OUTCOME
                    callers.isEmpty() -> ClusterRootKind.UNCALLED
                    else -> ClusterRootKind.REACHED_FROM_HIT
                }
            val reachedFrom =
                if (kind == ClusterRootKind.REACHED_FROM_HIT || kind == ClusterRootKind.CLASS_FINDING) {
                    hitCallers.map { toClusterMember(graph.nodes.getValue(it.method), it.method) }.sortedWith(clusterMemberComparator)
                } else {
                    emptyList()
                }
            buildUnreachedCluster(graph, clusterGraph, outcomes, classNodes, node, kind, reachedFrom, ::isNeverHit)
        }.sortedWith(
            compareByDescending<UnreachedClusterInfo> { it.membersTotal }
                .thenComparing({ it.root }, clusterMemberComparator)
                .thenComparing { cluster -> cluster.rootOutcome?.branchIndex ?: -1 },
        )
}

/**
 * Every class node, keyed by its [ClusterNode]. A never-initialised or never-instantiated class
 * stands for the methods [judgeClasses] says it covers. A class whose method nodes all come from a
 * complete baseline, since no manifest mentioned it, is never loaded and stands for all of them. A
 * class with no such method has no class node.
 */
private fun buildClassNodes(graph: CallGraph): Map<ClusterNode, ClassNodeInfo> {
    val judgement = judgeClasses()
    val classNodes = mutableMapOf<ClusterNode, ClassNodeInfo>()
    for ((className, finding) in judgement.findings) {
        val methods = judgement.covered[className].orEmpty().filter { (graph.nodes[it]?.hits ?: -1L) == 0L }
        if (methods.isNotEmpty()) classNodes[classNode(className)] = ClassNodeInfo(finding, methods)
    }
    graph.nodes
        .filter { (key, info) -> info.neverLoaded && key.className !in judgement.findings }
        .keys
        .groupBy { it.className }
        .forEach { (className, methods) -> classNodes[classNode(className)] = ClassNodeInfo(ClassFinding.NEVER_LOADED, methods) }
    return classNodes
}

/**
 * Grows [root]'s cluster by fixpoint: repeatedly add a never-hit node reachable from a current
 * member, once every one of that node's callers is itself already in the cluster. A node whose
 * callers sit outside the cluster, or a cycle of never-hit nodes with no outside caller, is never
 * added. Returns null for an outcome or class root when nothing but outcome nodes joined it.
 */
private fun buildUnreachedCluster(
    graph: CallGraph,
    clusterGraph: ClusterGraph,
    outcomes: Map<ClusterNode, OutcomeNode>,
    classNodes: Map<ClusterNode, ClassNodeInfo>,
    root: ClusterNode,
    rootKind: ClusterRootKind,
    reachedFrom: List<ClusterMember>,
    isNeverHit: (ClusterNode) -> Boolean,
): UnreachedClusterInfo? {
    val members = mutableSetOf(root)
    var changed = true
    while (changed) {
        changed = false
        for (member in members.toList()) {
            for (target in clusterGraph.calleesOf[member].orEmpty()) {
                if (target in members || !isNeverHit(target)) continue
                val callers = clusterGraph.callersOf[target].orEmpty()
                if (callers.isNotEmpty() && members.containsAll(callers)) {
                    members += target
                    changed = true
                }
            }
        }
    }
    if ((root.isClass || root.branchIndex != null) && members.none { it != root && it.branchIndex == null }) return null

    val methodsByClass = mutableMapOf<String, MutableList<NodeKey>>()
    for (member in members) {
        when {
            member.branchIndex != null -> {}
            member.isClass -> methodsByClass.getOrPut(member.method.className) { mutableListOf() } += classNodes.getValue(member).methods
            else -> methodsByClass.getOrPut(member.method.className) { mutableListOf() } += member.method
        }
    }
    val classSize = graph.nodes.keys.groupingBy { it.className }.eachCount()
    val wholeClasses = mutableListOf<WholeClassInfo>()
    val memberList = mutableListOf<ClusterMember>()
    var neverLoadedClasses = 0
    for ((className, keys) in methodsByClass) {
        val methods = keys.filter { it.methodName != CLASS_INIT }.map { toClusterMember(graph.nodes.getValue(it), it) }
        if (keys.any { graph.nodes.getValue(it).neverLoaded }) neverLoadedClasses++
        if (keys.size == classSize[className]) {
            wholeClasses += WholeClassInfo(className, classNodes[classNode(className)]?.finding, methods.size)
        } else {
            memberList += methods
        }
    }
    val rootOutcome =
        root.branchIndex?.let { branchIndex ->
            val outcome = outcomes.getValue(root)
            RootOutcome(branchIndex, outcome.line, outcome.site)
        }
    val rootMember =
        if (root.isClass) {
            val methods = classNodes.getValue(root).methods
            ClusterMember(root.method.className, "", "", methods.all { graph.nodes.getValue(it).neverLoaded })
        } else {
            toClusterMember(graph.nodes.getValue(root.method), root.method)
        }
    return UnreachedClusterInfo(
        root = rootMember,
        rootKind = rootKind,
        members = memberList.sortedWith(clusterMemberComparator),
        neverLoadedClasses = neverLoadedClasses,
        rootOutcome = rootOutcome,
        reachedFrom = reachedFrom,
        rootFinding = classNodes[root]?.finding,
        wholeClasses = wholeClasses.sortedBy { it.className },
    )
}

private fun toClusterMember(
    info: NodeInfo,
    key: NodeKey,
): ClusterMember = ClusterMember(key.className, key.methodName, key.methodDescriptor, info.neverLoaded)

/**
 * Every outcome node, keyed by its [ClusterNode]: a BRANCH probe that is neither inline nor
 * generated, whose hits summed across runs are zero, in a method [isHit] says has hits. Its site is
 * the one its run's METHOD probe lists with that branch index. See ADR 0039.
 */
private fun buildOutcomeNodes(isHit: (NodeKey) -> Boolean): Map<ClusterNode, OutcomeNode> =
    manifestProbes.entries
        .filter { (_, probe) ->
            probe.kind == ProbeKind.BRANCH &&
                probe.branchIndex != null &&
                !probe.inline &&
                probe.generatedBy == GeneratedBy.GENERATED_BY_NONE
        }.groupBy { (_, probe) -> ClusterNode(NodeKey(probe.className, probe.methodName, probe.methodDescriptor), probe.branchIndex) }
        .filter { (node, entries) -> isHit(node.method) && entries.sumOf { (key, _) -> latestHitsTotal[key] ?: 0L } == 0L }
        .mapValues { (node, entries) ->
            val site =
                entries.firstNotNullOfOrNull { (key, probe) ->
                    manifestBranchSites[InstanceMethodKey(key.run, key.classId, probe.methodName, probe.methodDescriptor)]
                        ?.firstOrNull { site -> site.outcomesList.any { it.branchIndex == node.branchIndex } }
                }
            OutcomeNode(entries.first().value.line, site)
        }

/**
 * Links every resolved call and every outcome node into the cluster graph. A call whose guard
 * names an outcome node in the caller's method starts at that outcome node, and any other call
 * starts at its method. A method [coveredBy] names is replaced by its class node at either end, and
 * a call that then starts and ends at the same node is dropped. An outcome node's one caller is the
 * outcome node its site's guard names, when that is another outcome node, and otherwise its method.
 */
private fun buildClusterGraph(
    graph: CallGraph,
    outcomes: Map<ClusterNode, OutcomeNode>,
    coveredBy: Map<NodeKey, ClusterNode>,
): ClusterGraph {
    val callersOf = mutableMapOf<ClusterNode, MutableSet<ClusterNode>>()
    val calleesOf = mutableMapOf<ClusterNode, MutableSet<ClusterNode>>()

    fun link(
        caller: ClusterNode,
        callee: ClusterNode,
    ) {
        callersOf.getOrPut(callee) { mutableSetOf() } += caller
        calleesOf.getOrPut(caller) { mutableSetOf() } += callee
    }
    fun nodeOf(key: NodeKey) = coveredBy[key] ?: ClusterNode(key)
    for ((caller, calls) in graph.calls) {
        for (call in calls) {
            val guardNode = call.guard?.let { ClusterNode(caller, it) }?.takeIf { it in outcomes }
            val from = guardNode ?: nodeOf(caller)
            val to = nodeOf(call.callee)
            if (from != to) link(from, to)
        }
    }
    for ((node, outcome) in outcomes) {
        val siteGuard = outcome.site?.takeIf { it.hasGuard() }?.guard
        val guardNode = siteGuard?.let { ClusterNode(node.method, it) }?.takeIf { it != node && it in outcomes }
        link(guardNode ?: ClusterNode(node.method), node)
    }
    return ClusterGraph(callersOf, calleesOf)
}

/**
 * Builds every node and resolves its edges against the known supertype graph. An edge resolves to
 * the union of two lookups, either of which may find nothing: the first node up the owner's
 * supertype chain, which is an inherited concrete declaration, and, for a virtual call, every node
 * with the same name and descriptor on a transitive subtype of the owner. Widening starts at the
 * owner, not at the declaring type: an abstract interface method has no node anywhere, so
 * requiring the up-walk to succeed would drop every edge into a pure interface, and a receiver
 * typed as the owner can only be the owner or one of its subtypes, never a sibling under some
 * ancestor. Each resolved call keeps its raw edge's guard. Declared classes and their supertypes
 * are consulted only from scans where every chunk has arrived; see [printNeverLoadedReport] for why
 * a partial scan cannot be diffed.
 */
private fun computeCallGraph(): CallGraph {
    val scansComplete = scans.values.all { it.complete }
    val declaredClasses = if (scansComplete) staticallyDeclaredClasses else emptyMap()
    val declaredSupertypes = if (scansComplete) staticallyDeclaredSupertypes else emptyMap()
    val nodes = buildClusterNodes(declaredClasses)
    val supertypesByClassName = buildSupertypesByClassName(declaredSupertypes)
    val reverseSubtypes = buildReverseSubtypes(supertypesByClassName)
    val calls = mutableMapOf<NodeKey, Set<ResolvedCall>>()
    for ((nodeKey, info) in nodes) {
        val resolved = mutableSetOf<ResolvedCall>()
        for (edge in info.edges) {
            val targets = mutableSetOf<NodeKey>()
            findDeclaringType(nodes, supertypesByClassName, edge.className, edge.methodName, edge.methodDescriptor)?.let {
                targets += NodeKey(it, edge.methodName, edge.methodDescriptor)
            }
            if (edge.virtual && edge.methodName != "<init>" && edge.methodName != "<clinit>") {
                targets += widenToSubtypes(nodes, reverseSubtypes, edge.className, edge.methodName, edge.methodDescriptor)
            }
            // A resolved call into a class is its first active use, which is what runs <clinit>;
            // no bytecode ever calls it directly. Same rule as YukonTestCollector.computeCallGraph.
            for (target in targets.toList()) {
                val typeInitializer = NodeKey(target.className, "<clinit>", "()V")
                if (typeInitializer in nodes) targets += typeInitializer
            }
            targets -= nodeKey
            targets.mapTo(resolved) { ResolvedCall(it, edge.guard) }
        }
        calls[nodeKey] = resolved
    }
    return CallGraph(nodes, calls)
}

/**
 * Every node: a manifest METHOD probe, non-inline and non-generated, merged across instances by
 * (class, method, descriptor) with hits summed and edges unioned with any matching declaration
 * from [declaredClasses]; plus, for a class [declaredClasses] names that no manifest ever
 * mentioned, each of its non-inline, non-generated declared methods, with zero hits. A generated
 * method is never a node: the compiler emits it again regardless of what the adopter does, so it
 * can neither root nor extend an unreached cluster. See ADR 0026.
 */
private fun buildClusterNodes(declaredClasses: Map<String, List<DeclaredMethodInfo>>): Map<NodeKey, NodeInfo> {
    val nodes = mutableMapOf<NodeKey, NodeInfo>()
    val manifestGroups =
        manifestProbes.entries
            .filter { (_, probe) -> probe.kind == ProbeKind.METHOD && !probe.inline && probe.generatedBy == GeneratedBy.GENERATED_BY_NONE }
            .groupBy { (_, probe) -> NodeKey(probe.className, probe.methodName, probe.methodDescriptor) }
    for ((nodeKey, entries) in manifestGroups) {
        val hits = entries.sumOf { (key, _) -> latestHitsTotal[key] ?: 0L }
        val edges = entries.flatMap { (key, _) -> manifestCallEdges[key].orEmpty() }.toMutableSet()
        declaredClasses[nodeKey.className]
            ?.filter { it.methodName == nodeKey.methodName && it.methodDescriptor == nodeKey.methodDescriptor }
            ?.forEach { edges += it.calls }
        val representative = entries.first().value
        nodes[nodeKey] = NodeInfo(line = representative.line, neverLoaded = false, hits = hits, edges = edges)
    }
    for ((className, methods) in declaredClasses) {
        if (className in dynamicallyKnownClassNames) continue
        for (method in methods) {
            if (method.inline || method.generatedBy != GeneratedBy.GENERATED_BY_NONE) continue
            val nodeKey = NodeKey(className, method.methodName, method.methodDescriptor)
            if (nodeKey in nodes) continue
            nodes[nodeKey] = NodeInfo(line = -1, neverLoaded = true, hits = 0L, edges = method.calls.toSet())
        }
    }
    return nodes
}

/** A class's supertypes, by name: from any instance's manifest record, or a declared-class record. */
private fun buildSupertypesByClassName(declaredSupertypes: Map<String, SupertypesInfo>): Map<String, SupertypesInfo> {
    val result = mutableMapOf<String, SupertypesInfo>()
    for ((key, info) in manifestProbes) {
        val supertypes = supertypesByClassId[InstanceClassIdKey(key.run, key.classId)] ?: continue
        result.putIfAbsent(info.className, supertypes)
    }
    for ((className, supertypes) in declaredSupertypes) {
        result.putIfAbsent(className, supertypes)
    }
    return result
}

/** Every known class name's direct subtypes, for widening a virtual call edge down. */
private fun buildReverseSubtypes(supertypesByClassName: Map<String, SupertypesInfo>): Map<String, List<String>> {
    val reverse = mutableMapOf<String, MutableList<String>>()
    for ((className, info) in supertypesByClassName) {
        info.superClassName?.let { reverse.getOrPut(it) { mutableListOf() } += className }
        info.interfaceNames.forEach { reverse.getOrPut(it) { mutableListOf() } += className }
    }
    return reverse
}

/**
 * Breadth-first walk from [owner] up through its supertypes to the first type with a node named
 * ([name], [desc]), [owner] itself included. Null if the whole chain, as far as it is known, never
 * reaches one.
 */
private fun findDeclaringType(
    nodes: Map<NodeKey, NodeInfo>,
    supertypesByClassName: Map<String, SupertypesInfo>,
    owner: String,
    name: String,
    desc: String,
): String? {
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    queue += owner
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!visited.add(current)) continue
        if (NodeKey(current, name, desc) in nodes) return current
        val info = supertypesByClassName[current] ?: continue
        info.superClassName?.let { queue += it }
        queue += info.interfaceNames
    }
    return null
}

/** Every transitive subtype of [declaringType], excluding itself, that has a matching ([name], [desc]) node. */
private fun widenToSubtypes(
    nodes: Map<NodeKey, NodeInfo>,
    reverseSubtypes: Map<String, List<String>>,
    declaringType: String,
    name: String,
    desc: String,
): Set<NodeKey> {
    val result = mutableSetOf<NodeKey>()
    val visited = mutableSetOf(declaringType)
    val queue = ArrayDeque<String>()
    queue += reverseSubtypes[declaringType].orEmpty()
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!visited.add(current)) continue
        val key = NodeKey(current, name, desc)
        if (key in nodes) result += key
        queue += reverseSubtypes[current].orEmpty()
    }
    return result
}

/**
 * Reports each dependency as unloaded, unreferenced, unreached, with no live reference, or used,
 * merged across instances by identity, plus every referenced class no loader could find. The
 * rules are ADR 0030's and live in [computeDependencyReport]; this only gathers what each instance
 * sent into the shape that function reads.
 */
private fun printDependencyReport() {
    formatDependencyReport(computeDependencyReport(dependencyViews())).forEach(::println)
}

/**
 * One [InstanceDependencyView] per run heard from, built from the maps the handlers fill. A run is
 * judged on its own, since its dependency ids and class ids mean nothing in another run.
 */
private fun dependencyViews(): List<InstanceDependencyView> {
    val runs = allRuns + manifestRuns + dependencyLocations.keys.map { it.run }
    return runs.sortedWith(compareBy({ it.serviceInstanceId }, { it.runId })).map { run ->
        val probes = manifestProbes.filterKeys { it.run == run }
        val classNamesById = probes.entries.associate { (key, info) -> key.classId to info.className }
        val methodReferences =
            probes
                .filterValues { it.kind == ProbeKind.METHOD }
                .map { (key, info) ->
                    HeldReferences(
                        className = info.className,
                        methodName = info.methodName,
                        methodDescriptor = info.methodDescriptor,
                        origin = ReferenceOrigin.MANIFEST_METHOD,
                        referencedClasses = probeReferencedClasses[key].orEmpty(),
                        inline = info.inline,
                        hits = latestHitsTotal[key] ?: 0L,
                    )
                }
        val classReferences =
            classLevelReferences
                .filterKeys { it.run == run }
                .map { (key, referenced) ->
                    val className = classNamesById[key.classId] ?: "class_id ${key.classId}"
                    HeldReferences(className, null, null, ReferenceOrigin.MANIFEST_CLASS, referenced)
                }
        val declaredReferences =
            baselineReferences
                .filterKeys { it.run == run }
                .flatMap { (key, declared) ->
                    listOf(HeldReferences(key.className, null, null, ReferenceOrigin.BASELINE, declared.classReferences)) +
                        declared.methods.map {
                            HeldReferences(
                                key.className,
                                it.methodName,
                                it.methodDescriptor,
                                ReferenceOrigin.BASELINE,
                                it.referencedClasses,
                                inline = it.inline,
                            )
                        }
                }
        val runScans = scans.filterKeys { it.run == run }.values
        InstanceDependencyView(
            instanceId = run.serviceInstanceId,
            referencesRecorded = run in runsRecordingReferences,
            baselineComplete = runScans.isNotEmpty() && runScans.all { it.complete },
            dependenciesListed = run in runsWithDependenciesListed,
            dependencies = dependencyLocations.filterKeys { it.run == run }.values.toList(),
            loadedClassesTotal =
                latestLoadedClassesTotal.filterKeys { it.run == run }.mapKeys { it.key.dependencyId },
            externalClasses = externalClasses.filterKeys { it.run == run }.mapKeys { it.key.className },
            references = methodReferences + classReferences + declaredReferences,
            loadedClassNames =
                probes.values.map { it.className }.toSet() +
                    skippedClasses.keys.filter { it.run == run }.map { it.className },
        )
    }
}
