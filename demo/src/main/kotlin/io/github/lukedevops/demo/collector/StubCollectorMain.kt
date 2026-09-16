package io.github.lukedevops.demo.collector

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.lukedevops.demo.DemoPorts
import io.github.lukedevops.yukon.proto.DeltaBatch
import io.github.lukedevops.yukon.proto.EndpointDiscoverySource
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
)

private data class SkippedInfo(
    val reason: String,
    val skippedAt: Long,
)

private data class DeclaredMethodInfo(
    val methodName: String,
    val methodDescriptor: String,
    val inline: Boolean,
)

private data class EndpointInfo(
    val verb: String,
    val routeTemplate: String,
    val verbatimTemplate: String,
    val framework: String,
    val discoverySource: EndpointDiscoverySource,
    val handlerClass: String?,
)

private val manifestProbes = ConcurrentHashMap<InstanceProbeKey, ProbeInfo>()
private val everHit = Collections.newSetFromMap(ConcurrentHashMap<InstanceProbeKey, Boolean>())
private val skippedClasses = ConcurrentHashMap<InstanceClassKey, SkippedInfo>()

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
            )
        dynamicallyKnownClassNames += location.className
    }
    for (skipped in manifest.skippedClassesList) {
        skippedClasses[InstanceClassKey(instanceId, skipped.className)] = SkippedInfo(skipped.reason, skipped.skippedAt)
        dynamicallyKnownClassNames += skipped.className
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
            )
    }
    for (disabled in manifest.disabledEndpointModulesList) {
        disabledEndpointModules[InstanceModuleKey(instanceId, disabled.module)] = disabled.reason
    }
    println(
        "[manifest] instance=$instanceId received ${manifest.probesList.size} probe locations " +
            "(known total: ${manifestProbes.size}) and ${manifest.skippedClassesList.size} skipped classes " +
            "(known total: ${skippedClasses.size}), ${manifest.endpointsList.size} endpoints " +
            "(known total: ${manifestEndpoints.size}) and ${manifest.disabledEndpointModulesList.size} disabled endpoint modules",
    )
    respondOk(exchange)
}

private fun handleStaticBaseline(exchange: HttpExchange) {
    val baseline = StaticBaseline.parseFrom(exchange.requestBody.readBytes())
    for (declaredClass in baseline.declaredClassesList) {
        staticallyDeclaredClasses[declaredClass.className] =
            declaredClass.methodsList.map { DeclaredMethodInfo(it.methodName, it.methodDescriptor, it.inline) }
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
    println(
        "[static-baseline] service=${baseline.resource.serviceName} chunk=${baseline.chunkIndex + 1}/${baseline.chunkCount} " +
            "declared_classes=${baseline.declaredClassesList.size} " +
            "statically_unsafe=${baseline.staticallyUnsafeClassesList.size} unreadable=${baseline.unreadableClassesList.size} " +
            "unprobed=${baseline.unprobedClassesList.size}",
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
    val (inlineNeverHit, judgeable) = neverHitKeys.partition { manifestProbes[it]?.inline == true }
    val judgeableTotal = judgeableKeys.count { manifestProbes[it]?.inline == false }
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
            println(
                "  NEVER HIT: ${info.className}#${info.methodName}:${info.line} " +
                    "[${info.kind}$branchSuffix] (instance ${key.serviceInstanceId}, class ${key.classId}, probe ${key.probeIndex})",
            )
        }
    // Kotlin inline functions copy their body into the caller, so their own probe reads near
    // zero however often they run: no "never hit" claim is made about them. See ADR 0022.
    println("inline (not judged): ${inlineNeverHit.size}")
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
 * parameter can go) or always supplied (the default value is dead). Both rules require the
 * target's own summed hit total to be above zero, and skip a target with no method probe at all
 * (an abstract interface method) or an inline target, the same reasons [printNeverHitReport]
 * excludes those. "Never supplied" is claimed only for a non-overridable target, since an
 * overridable target's omissions are spread across whichever override actually ran. See ADR 0021.
 */
private fun printOmissionReport() {
    println()
    println("=== yukon demo: optional argument report ===")
    val neverSupplied = mutableListOf<Pair<InstanceProbeKey, ProbeInfo>>()
    val alwaysSupplied = mutableListOf<Pair<InstanceProbeKey, ProbeInfo>>()
    for ((key, info) in manifestProbes) {
        if (info.kind != ProbeKind.OPTIONAL_ARGUMENT || info.inline) continue
        val targetClassName = info.targetClassName ?: info.className
        val targetHits =
            manifestProbes.entries
                .filter { (targetKey, targetInfo) ->
                    targetKey.serviceInstanceId == key.serviceInstanceId &&
                        targetInfo.kind == ProbeKind.METHOD &&
                        targetInfo.className == targetClassName &&
                        targetInfo.methodName == info.methodName &&
                        targetInfo.methodDescriptor == info.methodDescriptor
                }.sumOf { (targetKey, _) -> latestHitsTotal[targetKey] ?: 0L }
        if (targetHits <= 0L) continue
        val omitted = latestHitsTotal[key] ?: 0L
        if (!info.overridable && omitted == targetHits) neverSupplied += key to info
        if (omitted == 0L) alwaysSupplied += key to info
    }
    println("never supplied: ${neverSupplied.size}, always supplied: ${alwaysSupplied.size}")
    neverSupplied
        .sortedWith(compareBy({ it.second.targetClassName ?: it.second.className }, { it.second.methodName }, { it.second.parameterIndex }))
        .forEach { (key, info) ->
            val targetClassName = info.targetClassName ?: info.className
            println(
                "  NEVER SUPPLIED: $targetClassName#${info.methodName}(${info.parameterName}) (instance ${key.serviceInstanceId})",
            )
        }
    alwaysSupplied
        .sortedWith(compareBy({ it.second.targetClassName ?: it.second.className }, { it.second.methodName }, { it.second.parameterIndex }))
        .forEach { (key, info) ->
            val targetClassName = info.targetClassName ?: info.className
            println(
                "  ALWAYS SUPPLIED: $targetClassName#${info.methodName}(${info.parameterName}) (instance ${key.serviceInstanceId})",
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
            val handlerSuffix = info.handlerClass?.let { " handler=$it" } ?: ""
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
    val (allInline, neverLoaded) = neverLoadedAll.entries.partition { (_, methods) -> methods.isNotEmpty() && methods.all { it.inline } }
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
    // A class made only of inline functions is never loaded by a Kotlin caller at all, so its
    // absence here is not evidence it is dead. See ADR 0022.
    if (allInline.isNotEmpty()) {
        println("all inline (not judged): ${allInline.size}")
        allInline.sortedBy { it.key }.forEach { (className, _) -> println("  ALL INLINE: $className") }
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
