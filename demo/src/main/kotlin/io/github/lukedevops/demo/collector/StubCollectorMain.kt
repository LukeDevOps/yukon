package io.github.lukedevops.demo.collector

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.lukedevops.demo.DemoPorts
import io.github.lukedevops.yukon.proto.DeltaBatch
import io.github.lukedevops.yukon.proto.ProbeKind
import io.github.lukedevops.yukon.proto.ProbeManifest
import io.github.lukedevops.yukon.proto.StaticBaseline
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

private data class ProbeKey(
    val classId: Int,
    val probeIndex: Int,
)

private data class ProbeInfo(
    val className: String,
    val methodName: String,
    val line: Int,
    val kind: ProbeKind,
    val branchIndex: Int?,
)

private data class SkippedInfo(
    val reason: String,
    val skippedAt: Long,
)

private data class DeclaredMethodInfo(
    val methodName: String,
    val methodDescriptor: String,
)

private val manifestProbes = ConcurrentHashMap<ProbeKey, ProbeInfo>()
private val everHit = Collections.newSetFromMap(ConcurrentHashMap<ProbeKey, Boolean>())
private val skippedClasses = ConcurrentHashMap<String, SkippedInfo>()

// Any class name the reactive manifest has ever mentioned, whether it got probes or was skipped.
// Either way, it was loaded and reached the transform stage - the opposite of what the static
// baseline's declared-classes set is for.
private val dynamicallyKnownClassNames = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
private val staticallyDeclaredClasses = ConcurrentHashMap<String, List<DeclaredMethodInfo>>()
private val staticallyUnsafeClasses = ConcurrentHashMap<String, String>()
private val staticallyUnreadableClasses = ConcurrentHashMap<String, String>()

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
    var totalHits = 0L
    for (delta in batch.deltasList) {
        everHit += ProbeKey(delta.classId, delta.probeIndex)
        totalHits += delta.hitsSinceLastFlush
    }
    println(
        "[flush] service=${batch.resource.serviceName} instance=${batch.resource.serviceInstanceId} " +
            "probes_with_activity=${batch.deltasList.size} total_hits=$totalHits",
    )
    respondOk(exchange)
}

private fun handleManifest(exchange: HttpExchange) {
    val manifest = ProbeManifest.parseFrom(exchange.requestBody.readBytes())
    for (location in manifest.probesList) {
        manifestProbes[ProbeKey(location.classId, location.probeIndex)] =
            ProbeInfo(
                className = location.className,
                methodName = location.methodName,
                line = location.line,
                kind = location.kind,
                branchIndex = if (location.hasBranchIndex()) location.branchIndex else null,
            )
        dynamicallyKnownClassNames += location.className
    }
    for (skipped in manifest.skippedClassesList) {
        skippedClasses[skipped.className] = SkippedInfo(skipped.reason, skipped.skippedAt)
        dynamicallyKnownClassNames += skipped.className
    }
    println(
        "[manifest] received ${manifest.probesList.size} probe locations " +
            "(known total: ${manifestProbes.size}) and ${manifest.skippedClassesList.size} skipped classes " +
            "(known total: ${skippedClasses.size})",
    )
    respondOk(exchange)
}

private fun handleStaticBaseline(exchange: HttpExchange) {
    val baseline = StaticBaseline.parseFrom(exchange.requestBody.readBytes())
    for (declaredClass in baseline.declaredClassesList) {
        staticallyDeclaredClasses[declaredClass.className] =
            declaredClass.methodsList.map { DeclaredMethodInfo(it.methodName, it.methodDescriptor) }
    }
    for (unsafe in baseline.staticallyUnsafeClassesList) {
        staticallyUnsafeClasses[unsafe.className] = unsafe.reason
    }
    for (unreadable in baseline.unreadableClassesList) {
        staticallyUnreadableClasses[unreadable.className] = unreadable.reason
    }
    println(
        "[static-baseline] service=${baseline.resource.serviceName} declared_classes=${baseline.declaredClassesList.size} " +
            "statically_unsafe=${baseline.staticallyUnsafeClassesList.size} unreadable=${baseline.unreadableClassesList.size}",
    )
    respondOk(exchange)
}

private fun respondOk(exchange: HttpExchange) {
    exchange.sendResponseHeaders(200, -1)
    exchange.close()
}

private fun printNeverHitReport() {
    val neverHit = manifestProbes.keys.filter { it !in everHit }
    println()
    println("=== yukon demo: dead code report ===")
    println("known probes: ${manifestProbes.size}, ever hit: ${everHit.size}, never hit: ${neverHit.size}")
    if (manifestProbes.isNotEmpty()) {
        println("dead: %.1f%%".format(100.0 * neverHit.size / manifestProbes.size))
    }
    neverHit
        .mapNotNull { key -> manifestProbes[key]?.let { key to it } }
        .sortedWith(compareBy({ it.second.className }, { it.second.methodName }, { it.second.line }))
        .forEach { (key, info) ->
            val branchSuffix = info.branchIndex?.let { " branch#$it" } ?: ""
            println(
                "  NEVER HIT: ${info.className}#${info.methodName}:${info.line} " +
                    "[${info.kind}$branchSuffix] (class ${key.classId}, probe ${key.probeIndex})",
            )
        }
    if (skippedClasses.isNotEmpty()) {
        println("skipped (matched but could not be instrumented): ${skippedClasses.size}")
        skippedClasses.entries
            .sortedBy { it.key }
            .forEach { (className, info) -> println("  SKIPPED: $className - ${info.reason}") }
    }
    println("=====================================")
}

/**
 * A class is "never loaded" only if the static scan declared it AND the reactive manifest never
 * once mentioned it, by name, for any reason - not even as a skipped class. A class already
 * counted as statically unsafe or unreadable is excluded: the static scanner could not safely
 * classify it either way, so it is reported under its own heading instead.
 */
private fun printNeverLoadedReport() {
    val neverLoaded = staticallyDeclaredClasses.filterKeys { it !in dynamicallyKnownClassNames }
    println()
    println("=== yukon demo: never-loaded report (static baseline) ===")
    println(
        "statically declared: ${staticallyDeclaredClasses.size}, confirmed loaded: " +
            "${staticallyDeclaredClasses.keys.count { it in dynamicallyKnownClassNames }}, never loaded: ${neverLoaded.size}",
    )
    neverLoaded.entries
        .sortedBy { it.key }
        .forEach { (className, methods) ->
            val methodNames = methods.joinToString(", ") { it.methodName }
            println("  NEVER LOADED: $className (methods: $methodNames)")
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
