package io.github.lukedevops.yukon.testkit

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ProtoPayloadCodec
import io.github.lukedevops.yukon.export.SkippedClass
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * An embeddable collector that speaks the same wire protocol the Yukon agent sends: delta
 * batches, probe manifests, and static baselines, all protobuf over plain HTTP. An adopter's test
 * runs the real agent against [endpoint] and then asks this collector what it saw, instead of
 * querying any in-process registry.
 *
 * Every class name in this API is the dotted binary name the manifest carries: for example
 * `com.acme.OrderService`, `com.acme.OrdersKt` for a Kotlin file's top-level functions, or
 * `com.acme.Outer$Inner` for a nested class.
 *
 * A probe query for a class or method this collector has never heard of throws
 * [UnknownProbeException] rather than reading as "confirmed never hit". Collapsing "genuinely
 * dead" and "we have no idea" into the same `false` would be exactly the silent, confident, wrong
 * failure mode the rest of this agent is built to avoid.
 *
 * Close this with [close], typically from a `.use { }` block, once a test is done with it.
 */
class YukonTestCollector private constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
) : AutoCloseable {
    private data class ProbeKey(
        val serviceInstanceId: String,
        val classId: Int,
        val probeIndex: Int,
    )

    private data class StoredProbe(
        val className: String,
        val methodName: String,
        val methodDescriptor: String,
        val line: Int,
        val kind: ProbeKind,
        val branchIndex: Int?,
    )

    private data class ScanKey(
        val serviceInstanceId: String,
        val scannedAt: Long,
    )

    /** Accumulates one static baseline scan's chunks. Only merged into [consultedDeclaredNames] once complete. */
    private class ScanProgress(
        val chunkCount: Int,
    ) {
        val received: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        val declaredNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val complete: Boolean get() = received.size >= chunkCount
    }

    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private val deltaBatchSeq = AtomicLong(0)

    private val probesByKey = ConcurrentHashMap<ProbeKey, StoredProbe>()
    private val nameIndex = ConcurrentHashMap<String, MutableSet<ProbeKey>>()
    private val hitsByKey = ConcurrentHashMap<ProbeKey, Long>()
    private val skippedByClassName = ConcurrentHashMap<String, SkippedClass>()

    /** Every class name any manifest has ever mentioned, whether it got probes or was only reported as skipped. */
    private val dynamicallyKnownClassNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val scans = ConcurrentHashMap<ScanKey, ScanProgress>()
    private val completedScans: MutableSet<ScanKey> = ConcurrentHashMap.newKeySet()
    private val consultedDeclaredNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Base URL to pass as an agent's `endpoint=` option, for example `http://localhost:54321`. */
    val endpoint: String = "http://localhost:${server.address.port}"

    /**
     * Blocks until a delta batch arrives that was received after this call began, including an
     * empty one: the agent sends a delta batch on every flush tick, even when nothing changed, as
     * a liveness heartbeat. Throws [TimeoutException] if [timeout] elapses first.
     */
    fun awaitNextFlush(timeout: Duration) {
        val start = deltaBatchSeq.get()
        awaitUntil(timeout, "no delta batch arrived within $timeout") { deltaBatchSeq.get() > start }
    }

    /**
     * Blocks until some manifest, from any instance, has mentioned a probe for [className] and
     * [methodName]. A manifest and a delta batch from the same flush tick are sent concurrently by
     * the agent, so a newly loaded class's manifest entry can land after that tick's delta batch;
     * this lets a test wait for the manifest specifically. Throws [TimeoutException] if [timeout]
     * elapses first.
     */
    fun awaitProbe(
        className: String,
        methodName: String,
        timeout: Duration,
    ) {
        awaitUntil(timeout, "no manifest ever mentioned $className#$methodName within $timeout") {
            nameIndex[className]?.any { probesByKey[it]?.methodName == methodName } == true
        }
    }

    private fun awaitUntil(
        timeout: Duration,
        timeoutMessage: String,
        predicate: () -> Boolean,
    ) {
        val deadlineNanos = System.nanoTime() + timeout.toNanos()
        lock.withLock {
            while (!predicate()) {
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0) throw TimeoutException(timeoutMessage)
                condition.awaitNanos(remaining)
            }
        }
    }

    /**
     * True if any instance ever reported a nonzero `hits_total` for a METHOD-kind probe matching
     * [className] and [methodName]. `methodDescriptor` left null matches any overload.
     *
     * Throws [UnknownProbeException] if no such probe was ever declared; see that type's doc for
     * the four cases it distinguishes.
     */
    fun wasHit(
        className: String,
        methodName: String,
        methodDescriptor: String? = null,
    ): Boolean = findMethodProbes(className, methodName, methodDescriptor).any { (hitsByKey[it] ?: 0L) > 0L }

    /**
     * Sums, over every matching METHOD-kind probe, its latest known `hits_total` merged across
     * instances with max(). `methodDescriptor` left null sums every overload; given, it isolates
     * one.
     *
     * Throws [UnknownProbeException] if no such probe was ever declared.
     */
    fun hitCount(
        className: String,
        methodName: String,
        methodDescriptor: String? = null,
    ): Long = findMethodProbes(className, methodName, methodDescriptor).sumOf { hitsByKey[it] ?: 0L }

    private fun findMethodProbes(
        className: String,
        methodName: String,
        methodDescriptor: String?,
    ): List<ProbeKey> {
        val matches =
            nameIndex[className]?.filter { key ->
                val probe = probesByKey[key]
                probe != null &&
                    probe.kind == ProbeKind.METHOD &&
                    probe.methodName == methodName &&
                    (methodDescriptor == null || probe.methodDescriptor == methodDescriptor)
            } ?: emptyList()
        if (matches.isNotEmpty()) return matches
        throw unknownProbe(className, methodName, methodDescriptor)
    }

    private fun unknownProbe(
        className: String,
        methodName: String,
        methodDescriptor: String?,
    ): UnknownProbeException {
        skippedByClassName[className]?.let {
            return UnknownProbeException("$className: class was matched but could not be instrumented: ${it.reason}")
        }
        if (className in consultedDeclaredNames && className !in dynamicallyKnownClassNames) {
            return UnknownProbeException("$className: class was declared by the static baseline but never loaded in any instance")
        }
        if (nameIndex.containsKey(className)) {
            val descriptorSuffix = methodDescriptor?.let { " $it" } ?: ""
            return UnknownProbeException("$className: class is instrumented but has no probe for method $methodName$descriptorSuffix")
        }
        return UnknownProbeException(
            "$className: never mentioned by any manifest or static baseline " +
                "(not matched by includePackages, misspelled, or not loaded yet)",
        )
    }

    /**
     * Every manifest probe, method or branch, with no hit ever reported by any instance, sorted
     * by class name, method name, line, then branch index.
     */
    fun neverHit(): List<ProbeRef> =
        probesByKey.entries
            .filter { (key, _) -> (hitsByKey[key] ?: 0L) <= 0L }
            .map { (key, probe) ->
                ProbeRef(
                    key.serviceInstanceId,
                    probe.className,
                    probe.methodName,
                    probe.methodDescriptor,
                    probe.line,
                    probe.kind,
                    probe.branchIndex,
                )
            }.sortedWith(compareBy({ it.className }, { it.methodName }, { it.line }, { it.branchIndex ?: -1 }))

    /** Every class reported as matched but not instrumented by any manifest, distinct by class name, sorted by name. */
    fun skippedClasses(): List<SkippedClass> = skippedByClassName.values.sortedBy { it.className }

    /**
     * Class names declared by a complete static baseline scan that no manifest, from any
     * instance, has ever mentioned as a probe's class or as a skipped class.
     *
     * Throws [IllegalStateException] if no static baseline scan has ever completed: an empty list
     * would read as "nothing is dead", when the real answer is "no idea yet". A class in the
     * unsafe, unreadable, or unprobed baseline buckets is never counted as declared here, so it
     * never appears in this list either.
     */
    fun neverLoaded(): List<String> {
        check(completedScans.isNotEmpty()) { "no complete static baseline scan has been received yet" }
        return consultedDeclaredNames.filter { it !in dynamicallyKnownClassNames }.sorted()
    }

    override fun close() {
        server.stop(0)
        executor.shutdown()
    }

    private fun handleDeltaBatch(exchange: HttpExchange) {
        val bytes = exchange.requestBody.readBytes()
        val batch =
            try {
                ProtoPayloadCodec.decodeDeltaBatch(bytes)
            } catch (e: Exception) {
                respond(exchange, 400)
                return
            }
        val instanceId = batch.resource.serviceInstanceId
        for (delta in batch.deltas) {
            val key = ProbeKey(instanceId, delta.classId, delta.probeIndex)
            hitsByKey.merge(key, delta.hitsTotal, ::maxOf)
        }
        deltaBatchSeq.incrementAndGet()
        respond(exchange, 200)
        signalAll()
    }

    private fun handleManifest(exchange: HttpExchange) {
        val bytes = exchange.requestBody.readBytes()
        val manifest =
            try {
                ProtoPayloadCodec.decodeProbeManifest(bytes)
            } catch (e: Exception) {
                respond(exchange, 400)
                return
            }
        val instanceId = manifest.serviceInstanceId
        for (location in manifest.probes) {
            val key = ProbeKey(instanceId, location.classId, location.probeIndex)
            probesByKey[key] =
                StoredProbe(
                    location.className,
                    location.methodName,
                    location.methodDescriptor,
                    location.line,
                    location.kind,
                    location.branchIndex,
                )
            nameIndex.computeIfAbsent(location.className) { ConcurrentHashMap.newKeySet() }.add(key)
            dynamicallyKnownClassNames += location.className
        }
        for (skipped in manifest.skippedClasses) {
            skippedByClassName.putIfAbsent(skipped.className, skipped)
            dynamicallyKnownClassNames += skipped.className
        }
        respond(exchange, 200)
        signalAll()
    }

    private fun handleStaticBaseline(exchange: HttpExchange) {
        val bytes = exchange.requestBody.readBytes()
        val baseline =
            try {
                ProtoPayloadCodec.decodeStaticBaseline(bytes)
            } catch (e: Exception) {
                respond(exchange, 400)
                return
            }
        val scanKey = ScanKey(baseline.resource.serviceInstanceId, baseline.scannedAt)
        val progress = scans.computeIfAbsent(scanKey) { ScanProgress(baseline.chunkCount) }
        val wasComplete = progress.complete
        progress.received += baseline.chunkIndex
        progress.declaredNames += baseline.declaredClasses.map { it.className }
        if (!wasComplete && progress.complete) {
            consultedDeclaredNames += progress.declaredNames
            completedScans += scanKey
        }
        respond(exchange, 200)
        signalAll()
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
    ) {
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
    }

    private fun signalAll() {
        lock.withLock { condition.signalAll() }
    }

    companion object {
        /**
         * Starts a collector bound to `localhost`. [port] `0` (the default) picks any free port,
         * read back afterwards from [endpoint].
         */
        fun start(port: Int = 0): YukonTestCollector {
            val httpServer = HttpServer.create(InetSocketAddress("localhost", port), 0)
            val executor =
                Executors.newCachedThreadPool { runnable -> Thread(runnable, "yukon-testkit-http").apply { isDaemon = true } }
            httpServer.executor = executor
            val collector = YukonTestCollector(httpServer, executor)
            httpServer.createContext("/v1/yukon/deltas", collector::handleDeltaBatch)
            httpServer.createContext("/v1/yukon/manifest", collector::handleManifest)
            httpServer.createContext("/v1/yukon/static-baseline", collector::handleStaticBaseline)
            httpServer.start()
            return collector
        }
    }
}

/** One probe's identity and location, as reported by a manifest. See [YukonTestCollector] for the class name format. */
data class ProbeRef(
    val serviceInstanceId: String,
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val line: Int,
    val kind: ProbeKind,
    val branchIndex: Int?,
)

/**
 * Thrown when a query names a class or method [YukonTestCollector] has no probe for, instead of
 * reading as "confirmed never hit". The message names one of four cases, checked in this order:
 *
 * 1. The class was matched by `includePackages` but ByteBuddy could not instrument it, so it was
 *    reported as skipped.
 * 2. The class was declared by a complete static baseline scan, but no manifest from any instance
 *    ever mentioned it: it never loaded during the observation window.
 * 3. The class is instrumented and has manifest probes, but none match the requested method name
 *    or descriptor.
 * 4. The class was never mentioned anywhere at all: not matched by `includePackages`, misspelled,
 *    or simply not loaded yet.
 */
class UnknownProbeException(
    message: String,
) : RuntimeException(message)
