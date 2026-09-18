package io.github.lukedevops.demo.collector

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.lukedevops.demo.DemoPorts
import io.github.lukedevops.yukon.proto.DeltaBatch
import io.github.lukedevops.yukon.proto.EndpointDiscoverySource
import io.github.lukedevops.yukon.proto.GeneratedBy
import io.github.lukedevops.yukon.proto.ProbeKind
import io.github.lukedevops.yukon.proto.ProbeManifest
import io.github.lukedevops.yukon.proto.StaticBaseline
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * [classId] is assigned independently by each agent instance's own registry, in that process's
 * own class-loading order, so the same [classId] can mean a different class in two different
 * instances. Every probe key used by this stub collector is scoped to [serviceInstanceId] for
 * that reason: `ProbeManifest` now carries its own `service_instance_id`, the same as
 * `DeltaBatch`'s resource, so there is always an instance to key on.
 */
private data class InstanceProbeKey(
    val serviceInstanceId: String,
    val classId: Int,
    val probeIndex: Int,
)

/** Scopes a skipped class's name to the instance that reported it, for the same reason as [InstanceProbeKey]. */
private data class InstanceClassKey(
    val serviceInstanceId: String,
    val className: String,
)

/** Scopes an endpoint id to the instance that reported it, for the same reason as [InstanceProbeKey]. */
private data class InstanceEndpointKey(
    val serviceInstanceId: String,
    val endpointId: Int,
)

/** Scopes a disabled endpoint module's name to the instance that reported it. */
private data class InstanceModuleKey(
    val serviceInstanceId: String,
    val module: String,
)

/**
 * Groups every omission probe naming one optional parameter, within one instance: the target
 * class (`targetClassName ?: className`), method, descriptor, and parameter index. A group can
 * hold more than one probe: a Scala constructor default gets both a module getter, resolved
 * across the class boundary, and that class's own static forwarder for the same getter name,
 * resolved in class, both landing on the same target. See ADR 0023.
 */
private data class OmissionTargetKey(
    val serviceInstanceId: String,
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
)

/** One call edge read from a METHOD probe's own bytecode. See ADR 0024. */
private data class CallEdgeInfo(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val virtual: Boolean,
)

/** A class's superclass and direct interfaces, as reported by one instance. See ADR 0024. */
private data class SupertypesInfo(
    val superClassName: String?,
    val interfaceNames: List<String>,
)

/** Scopes a class_id's supertypes record to the instance that reported it, for the same reason as [InstanceProbeKey]. */
private data class InstanceClassIdKey(
    val serviceInstanceId: String,
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
private val everHit = Collections.newSetFromMap(ConcurrentHashMap<InstanceProbeKey, Boolean>())
private val skippedClasses = ConcurrentHashMap<InstanceClassKey, SkippedInfo>()

// Call edges (ADR 0024), stored per METHOD probe rather than only counted at receipt, since a
// later chunk's cluster logic needs the actual callees, not just how many arrived.
private val manifestCallEdges = ConcurrentHashMap<InstanceProbeKey, List<CallEdgeInfo>>()
private val classSupertypes = ConcurrentHashMap<InstanceClassIdKey, SupertypesInfo>()

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
private val staticallyDeclaredClasses = ConcurrentHashMap<String, List<DeclaredMethodInfo>>()

// A declared class's superclass and interfaces, read the same way as a loaded class's
// ClassSupertypes record. See ADR 0024.
private val staticallyDeclaredSupertypes = ConcurrentHashMap<String, SupertypesInfo>()
private val staticallyUnsafeClasses = ConcurrentHashMap<String, String>()
private val staticallyUnreadableClasses = ConcurrentHashMap<String, String>()
private val staticallyUnprobedClasses = ConcurrentHashMap<String, String>()

/** One static scan, identified by (instance, scanned_at), arrives as chunk_count chunks; only a complete scan may be diffed. */
private data class ScanKey(
    val serviceInstanceId: String,
    val scannedAt: Long,
)

private data class ScanProgress(
    val chunkCount: Int,
    val received: MutableSet<Int> = ConcurrentHashMap.newKeySet(),
) {
    val complete: Boolean get() = received.size == chunkCount
}

private val scans = ConcurrentHashMap<ScanKey, ScanProgress>()

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

/** The resolved call graph: every node, its resolved outgoing edges, and the reverse (caller) index. */
private class CallGraph(
    val nodes: Map<NodeKey, NodeInfo>,
    val resolvedEdges: Map<NodeKey, Set<NodeKey>>,
    val callersOf: Map<NodeKey, Set<NodeKey>>,
)

/** Which of the two root shapes ADR 0024 distinguishes an [UnreachedClusterInfo] by. */
private enum class ClusterRootKind { REACHED_FROM_HIT, UNCALLED }

/** One member of an unreached cluster, printed by [printUnreachedClusterReport]. */
private data class ClusterMember(
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val neverLoaded: Boolean,
)

/** A root plus every never-hit method reachable from it whose every in-scope caller is itself in the cluster. */
private data class UnreachedClusterInfo(
    val root: ClusterMember,
    val rootKind: ClusterRootKind,
    val members: List<ClusterMember>,
    val neverLoadedClasses: Int,
)

/**
 * Stands in for the real collector, which lives outside this repo.
 *
 * It decodes the same generated protobuf classes the agent sends. It keeps the manifest and
 * hit history in memory. On shutdown, it prints two reports: "never hit" (manifest probes with
 * no delta that ever reported a hit) and, since the demo server opts into
 * `staticBaselineEnabled=true`, "never loaded" (classes the static scan found that never once
 * appeared in the reactive manifest at all).
 */
fun main() {
    val server = HttpServer.create(InetSocketAddress(DemoPorts.COLLECTOR_PORT), 0)
    server.createContext("/v1/yukon/deltas", ::handleDeltaBatch)
    server.createContext("/v1/yukon/manifest", ::handleManifest)
    server.createContext("/v1/yukon/static-baseline", ::handleStaticBaseline)
    server.createContext("/__shutdown", ::handleShutdown)
    server.start()
    println("yukon stub collector listening on ${DemoPorts.COLLECTOR_PORT}")

    Runtime.getRuntime().addShutdownHook(
        Thread {
            printNeverHitReport()
            printOmissionReport()
            printEndpointReport()
            printNeverLoadedReport()
            printUnreachedClusterReport()
        },
    )
}

/** Exits in-process, instead of relying on SIGTERM. SIGTERM can drop the shutdown-hook report mid-write. */
private fun handleShutdown(exchange: HttpExchange) {
    respondOk(exchange)
    Thread { System.exit(0) }.start()
}

private fun handleDeltaBatch(exchange: HttpExchange) {
    val batch = DeltaBatch.parseFrom(exchange.requestBody.readBytes())
    val instanceId = batch.resource.serviceInstanceId
    for (delta in batch.deltasList) {
        val key = InstanceProbeKey(instanceId, delta.classId, delta.probeIndex)
        everHit += key
        latestHitsTotal.merge(key, delta.hitsTotal, ::maxOf)
    }
    for (delta in batch.endpointDeltasList) {
        val key = InstanceEndpointKey(instanceId, delta.endpointId)
        latestEndpointHitsTotal.merge(key, delta.hitsTotal, ::maxOf)
    }
    val totalHits = latestHitsTotal.values.sum()
    val totalEndpointHits = latestEndpointHitsTotal.values.sum()
    println(
        "[flush] service=${batch.resource.serviceName} instance=${batch.resource.serviceInstanceId} " +
            "probes_with_activity=${batch.deltasList.size} total_hits=$totalHits " +
            "endpoints_with_activity=${batch.endpointDeltasList.size} total_endpoint_hits=$totalEndpointHits",
    )
    respondOk(exchange)
}

private fun handleManifest(exchange: HttpExchange) {
    val manifest = ProbeManifest.parseFrom(exchange.requestBody.readBytes())
    val instanceId = manifest.serviceInstanceId
    for (location in manifest.probesList) {
        manifestProbes[InstanceProbeKey(instanceId, location.classId, location.probeIndex)] =
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
            )
        dynamicallyKnownClassNames += location.className
        if (location.callsList.isNotEmpty()) {
            manifestCallEdges[InstanceProbeKey(instanceId, location.classId, location.probeIndex)] =
                location.callsList.map { CallEdgeInfo(it.className, it.methodName, it.methodDescriptor, it.virtual) }
        }
    }
    for (skipped in manifest.skippedClassesList) {
        skippedClasses[InstanceClassKey(instanceId, skipped.className)] = SkippedInfo(skipped.reason, skipped.skippedAt)
        dynamicallyKnownClassNames += skipped.className
    }
    for (supertypes in manifest.classSupertypesList) {
        classSupertypes[InstanceClassIdKey(instanceId, supertypes.classId)] =
            SupertypesInfo(supertypes.superClassName.ifEmpty { null }, supertypes.interfaceNamesList)
    }
    for (endpoint in manifest.endpointsList) {
        manifestEndpoints[InstanceEndpointKey(instanceId, endpoint.endpointId)] =
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
        disabledEndpointModules[InstanceModuleKey(instanceId, disabled.module)] = disabled.reason
    }
    val callEdgeCount = manifest.probesList.sumOf { it.callsList.size }
    println(
        "[manifest] instance=$instanceId received ${manifest.probesList.size} probe locations " +
            "(known total: ${manifestProbes.size}) and ${manifest.skippedClassesList.size} skipped classes " +
            "(known total: ${skippedClasses.size}), ${manifest.endpointsList.size} endpoints " +
            "(known total: ${manifestEndpoints.size}) and ${manifest.disabledEndpointModulesList.size} disabled endpoint modules, " +
            "$callEdgeCount call edges (known total: ${manifestCallEdges.values.sumOf { it.size }}) and " +
            "${manifest.classSupertypesList.size} class supertypes records (known total: ${classSupertypes.size})",
    )
    respondOk(exchange)
}

private fun handleStaticBaseline(exchange: HttpExchange) {
    val baseline = StaticBaseline.parseFrom(exchange.requestBody.readBytes())
    for (declaredClass in baseline.declaredClassesList) {
        staticallyDeclaredClasses[declaredClass.className] =
            declaredClass.methodsList.map {
                DeclaredMethodInfo(
                    it.methodName,
                    it.methodDescriptor,
                    it.inline,
                    it.callsList.map { call -> CallEdgeInfo(call.className, call.methodName, call.methodDescriptor, call.virtual) },
                    it.generatedBy,
                )
            }
        staticallyDeclaredSupertypes[declaredClass.className] =
            SupertypesInfo(declaredClass.superClassName.ifEmpty { null }, declaredClass.interfaceNamesList)
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
        scans.computeIfAbsent(ScanKey(baseline.resource.serviceInstanceId, baseline.scannedAt)) { ScanProgress(baseline.chunkCount) }
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

private fun respondOk(exchange: HttpExchange) {
    exchange.sendResponseHeaders(200, -1)
    exchange.close()
}

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
    val (generatedNeverHit, judgeable) =
        notInlineNeverHit.partition { manifestProbes[it]?.generatedBy != GeneratedBy.GENERATED_BY_NONE }
    val judgeableTotal =
        judgeableKeys.count { key ->
            val probe = manifestProbes[key]
            probe != null && !probe.inline && probe.generatedBy == GeneratedBy.GENERATED_BY_NONE
        }
    println()
    println("=== yukon demo: dead code report ===")
    println("known probes: ${manifestProbes.size}, ever hit: ${everHit.size}, never hit: ${judgeable.size}")
    if (judgeableTotal > 0) {
        println("dead: %.1f%%".format(100.0 * judgeable.size / judgeableTotal))
    }
    judgeable
        .mapNotNull { key -> manifestProbes[key]?.let { key to it } }
        .sortedWith(compareBy({ it.second.className }, { it.second.methodName }, { it.second.line }))
        .forEach { (key, info) ->
            val branchSuffix = info.branchIndex?.let { " branch#$it" } ?: ""
            val inlinedFromSuffix = info.inlinedFromClassName?.let { " (inlined from $it)" } ?: ""
            println(
                "  NEVER HIT: ${info.className}#${info.methodName}:${info.line} " +
                    "[${info.kind}$branchSuffix]$inlinedFromSuffix (instance ${key.serviceInstanceId}, class ${key.classId}, probe ${key.probeIndex})",
            )
        }
    // Kotlin inline functions copy their body into the caller, so their own probe reads near
    // zero however often they run: no "never hit" claim is made about them. See ADR 0022.
    println("inline (not judged): ${inlineNeverHit.size}")
    println("generated (not judged): ${generatedNeverHit.size}")
    if (skippedClasses.isNotEmpty()) {
        println("skipped (matched but could not be instrumented): ${skippedClasses.size}")
        skippedClasses.entries
            .sortedWith(compareBy({ it.key.serviceInstanceId }, { it.key.className }))
            .forEach { (key, info) -> println("  SKIPPED: ${key.className} (instance ${key.serviceInstanceId}) - ${info.reason}") }
    }
    println("=====================================")
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
                    key.serviceInstanceId,
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
                    targetKey.serviceInstanceId == groupKey.serviceInstanceId &&
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
                    "(instance ${groupKey.serviceInstanceId})",
            )
        }
    alwaysSupplied
        .sortedWith(compareBy({ it.first.targetClassName }, { it.first.methodName }, { it.first.parameterIndex }))
        .forEach { (groupKey, info) ->
            println(
                "  ALWAYS SUPPLIED: ${groupKey.targetClassName}#${groupKey.methodName}(${info.parameterName}) " +
                    "(instance ${groupKey.serviceInstanceId})",
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
            .sortedWith(compareBy({ it.key.serviceInstanceId }, { it.key.module }))
            .forEach { (key, reason) -> println("  DISABLED: ${key.module} (instance ${key.serviceInstanceId}) - $reason") }
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
                "  INCOMPLETE SCAN: instance ${key.serviceInstanceId} scanned_at ${key.scannedAt} received " +
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
            println("  NEVER LOADED: $className (methods: $methodNames)")
        }
    // A class made only of inline functions is never loaded by a Kotlin caller at all, and the
    // compiler emits a generated method again regardless of what the adopter does, so a class
    // whose every method is one or the other never loading is not evidence it is dead. See ADR
    // 0022 and ADR 0026.
    if (allInlineOrGenerated.isNotEmpty()) {
        println("all inline or generated (not judged): ${allInlineOrGenerated.size}")
        allInlineOrGenerated.sortedBy { it.key }.forEach { (className, _) -> println("  ALL INLINE OR GENERATED: $className") }
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
 * class supertypes, and complete-baseline declarations this stub already stores. See ADR 0024 and
 * CONTEXT.md, "Unreached cluster".
 */
private fun printUnreachedClusterReport() {
    println()
    println("=== yukon demo: unreached clusters ===")
    val clusters = computeUnreachedClusters()
    println("clusters: ${clusters.size}")
    val routesByHandler = routesByHandler()
    clusters.forEach { cluster ->
        val rootLabel = if (cluster.rootKind == ClusterRootKind.REACHED_FROM_HIT) "reached from hit" else "uncalled"
        val routes = routesByHandler[NodeKey(cluster.root.className, cluster.root.methodName, cluster.root.methodDescriptor)]
        val routesSuffix = routes?.let { " routes=${it.joinToString(", ", "[", "]")}" } ?: ""
        println(
            "UNREACHED CLUSTER: root ${cluster.root.className}#${cluster.root.methodName} ($rootLabel), " +
                "${cluster.members.size} methods, ${cluster.neverLoadedClasses} never-loaded classes$routesSuffix",
        )
        cluster.members.forEach { member ->
            val suffix = if (member.neverLoaded) " (never loaded)" else ""
            println("  ${member.className}#${member.methodName}$suffix")
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
 * Every unreached cluster in the call graph, sorted by member count descending, then by root. A
 * root is a never-hit method with at least one hit caller ([ClusterRootKind.REACHED_FROM_HIT]) or
 * with no in-scope caller at all ([ClusterRootKind.UNCALLED]).
 */
private fun computeUnreachedClusters(): List<UnreachedClusterInfo> {
    val graph = computeCallGraph()

    fun isHit(key: NodeKey) = (graph.nodes[key]?.hits ?: 0L) > 0L

    val roots =
        graph.nodes.keys.filter { !isHit(it) }.mapNotNull { key ->
            val callers = graph.callersOf[key].orEmpty()
            when {
                callers.isEmpty() -> key to ClusterRootKind.UNCALLED
                callers.any { isHit(it) } -> key to ClusterRootKind.REACHED_FROM_HIT
                else -> null
            }
        }

    return roots
        .map { (rootKey, rootKind) -> buildUnreachedCluster(graph, rootKey, rootKind, ::isHit) }
        .sortedWith(
            compareByDescending<UnreachedClusterInfo> { it.members.size }
                .thenComparing({ it.root }, clusterMemberComparator),
        )
}

/**
 * Grows [rootKey]'s cluster by fixpoint: repeatedly add a never-hit node reachable by a resolved
 * edge from a current member, once every one of that node's callers is itself already in the
 * cluster. A node whose callers sit outside the cluster, or a cycle of never-hit nodes with no
 * outside caller, is never added.
 */
private fun buildUnreachedCluster(
    graph: CallGraph,
    rootKey: NodeKey,
    rootKind: ClusterRootKind,
    isHit: (NodeKey) -> Boolean,
): UnreachedClusterInfo {
    val members = mutableSetOf(rootKey)
    var changed = true
    while (changed) {
        changed = false
        for (member in members.toList()) {
            for (target in graph.resolvedEdges[member].orEmpty()) {
                if (target in members || isHit(target)) continue
                val callers = graph.callersOf[target].orEmpty()
                if (callers.isNotEmpty() && members.containsAll(callers)) {
                    members += target
                    changed = true
                }
            }
        }
    }
    val memberList = members.map { toClusterMember(graph.nodes.getValue(it), it) }.sortedWith(clusterMemberComparator)
    val neverLoadedClasses =
        memberList
            .filter { it.neverLoaded }
            .map { it.className }
            .distinct()
            .size
    return UnreachedClusterInfo(toClusterMember(graph.nodes.getValue(rootKey), rootKey), rootKind, memberList, neverLoadedClasses)
}

private fun toClusterMember(
    info: NodeInfo,
    key: NodeKey,
): ClusterMember = ClusterMember(key.className, key.methodName, key.methodDescriptor, info.neverLoaded)

/**
 * Builds every node, resolves its edges against the known supertype graph, and indexes callers.
 * An edge resolves to the union of two lookups, either of which may find nothing: the first node
 * up the owner's supertype chain, which is an inherited concrete declaration, and, for a virtual
 * call, every node with the same name and descriptor on a transitive subtype of the owner.
 * Widening starts at the owner, not at the declaring type: an abstract interface method has no
 * node anywhere, so requiring the up-walk to succeed would drop every edge into a pure interface,
 * and a receiver typed as the owner can only be the owner or one of its subtypes, never a sibling
 * under some ancestor. Declared classes and their supertypes are consulted only from scans where
 * every chunk has arrived; see [printNeverLoadedReport] for why a partial scan cannot be diffed.
 */
private fun computeCallGraph(): CallGraph {
    val scansComplete = scans.values.all { it.complete }
    val declaredClasses = if (scansComplete) staticallyDeclaredClasses else emptyMap()
    val declaredSupertypes = if (scansComplete) staticallyDeclaredSupertypes else emptyMap()
    val nodes = buildClusterNodes(declaredClasses)
    val supertypesByClassName = buildSupertypesByClassName(declaredSupertypes)
    val reverseSubtypes = buildReverseSubtypes(supertypesByClassName)
    val resolvedEdges = mutableMapOf<NodeKey, Set<NodeKey>>()
    val callersOf = mutableMapOf<NodeKey, MutableSet<NodeKey>>()
    for ((nodeKey, info) in nodes) {
        val targets = mutableSetOf<NodeKey>()
        for (edge in info.edges) {
            findDeclaringType(nodes, supertypesByClassName, edge.className, edge.methodName, edge.methodDescriptor)?.let {
                targets += NodeKey(it, edge.methodName, edge.methodDescriptor)
            }
            if (edge.virtual && edge.methodName != "<init>" && edge.methodName != "<clinit>") {
                targets += widenToSubtypes(nodes, reverseSubtypes, edge.className, edge.methodName, edge.methodDescriptor)
            }
        }
        // A resolved call into a class is its first active use, which is what runs <clinit>;
        // no bytecode ever calls it directly. Same rule as YukonTestCollector.computeCallGraph.
        for (target in targets.toList()) {
            val typeInitializer = NodeKey(target.className, "<clinit>", "()V")
            if (typeInitializer in nodes) targets += typeInitializer
        }
        targets -= nodeKey
        resolvedEdges[nodeKey] = targets
        for (target in targets) callersOf.getOrPut(target) { mutableSetOf() } += nodeKey
    }
    return CallGraph(nodes, resolvedEdges, callersOf)
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
        val supertypes = classSupertypes[InstanceClassIdKey(key.serviceInstanceId, key.classId)] ?: continue
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
