package io.github.lukedevops.demo.collector

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.lukedevops.demo.DemoPorts
import io.github.lukedevops.yukon.proto.DeltaBatch
import io.github.lukedevops.yukon.proto.ProbeKind
import io.github.lukedevops.yukon.proto.ProbeManifest
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

private val manifestProbes = ConcurrentHashMap<ProbeKey, ProbeInfo>()
private val everHit = Collections.newSetFromMap(ConcurrentHashMap<ProbeKey, Boolean>())
private val skippedClasses = ConcurrentHashMap<String, SkippedInfo>()

/**
 * Stands in for the real collector that lives outside this repo: decodes the
 * same generated protobuf classes the agent sends, keeps the manifest and
 * hit history in memory, and prints a "never hit" report on shutdown:
 * manifest probes with no delta that ever reported a hit.
 */
fun main() {
    val server = HttpServer.create(InetSocketAddress(DemoPorts.COLLECTOR_PORT), 0)
    server.createContext("/v1/yukon/deltas", ::handleDeltaBatch)
    server.createContext("/v1/yukon/manifest", ::handleManifest)
    server.createContext("/__shutdown", ::handleShutdown)
    server.start()
    println("yukon stub collector listening on ${DemoPorts.COLLECTOR_PORT}")

    Runtime.getRuntime().addShutdownHook(Thread(::printNeverHitReport))
}

/** Exits in-process instead of via SIGTERM, which can drop the shutdown-hook report mid-write. */
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
    }
    for (skipped in manifest.skippedClassesList) {
        skippedClasses[skipped.className] = SkippedInfo(skipped.reason, skipped.skippedAt)
    }
    println(
        "[manifest] received ${manifest.probesList.size} probe locations " +
            "(known total: ${manifestProbes.size}) and ${manifest.skippedClassesList.size} skipped classes " +
            "(known total: ${skippedClasses.size})",
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
