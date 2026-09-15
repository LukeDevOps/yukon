package io.github.lukedevops.yukon.testkit

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.lukedevops.yukon.export.DisabledEndpointModule
import io.github.lukedevops.yukon.export.EndpointDiscoverySource
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ProtoPayloadCodec
import io.github.lukedevops.yukon.export.SkippedClass
import io.github.lukedevops.yukon.registry.RouteTemplateNormalizer
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
 * Endpoint queries ([wasCalled], [callCount], [neverCalled], [endpoints]) follow the same rule,
 * throwing [UnknownEndpointException] for a `(verb, route template)` no manifest has mentioned.
 * An endpoint's identity is the pair alone, never `endpoint_id`: `endpoint_id` is assigned
 * independently by each instance's own registry, so an endpoint registered by several instances
 * merges into one [EndpointRef] whose call count sums every instance's latest total, the same
 * cross-instance aggregation [hitCount] already does for method probes by class and method name.
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
        val inline: Boolean,
    )

    private data class ScanKey(
        val serviceInstanceId: String,
        val scannedAt: Long,
    )

    /** Cross-instance endpoint identity: the pair alone, never `endpoint_id`. See the class KDoc. */
    private data class EndpointIdentity(
        val verb: String,
        val routeTemplate: String,
    )

    private data class InstanceEndpointKey(
        val serviceInstanceId: String,
        val endpointId: Int,
    )

    /** Accumulates one static baseline scan's chunks. Only merged into [consultedDeclaredNames] once complete. */
    private class ScanProgress(
        val chunkCount: Int,
    ) {
        val received: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        val declaredNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Declared classes whose every declared method is inline; see [YukonTestCollector.neverLoaded]. */
        val allInlineNames: MutableSet<String> = ConcurrentHashMap.newKeySet()
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

    /** Declared classes, from a complete scan, whose every declared method is inline. */
    private val consultedAllInlineNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Latest delivered record per endpoint identity, across every instance; see [handleManifest]. */
    private val endpointRefsByIdentity = ConcurrentHashMap<EndpointIdentity, EndpointRef>()

    /** Every `(instance, endpoint_id)` ever reported for an identity, so [callCount] can sum each instance's latest total. */
    private val endpointKeysByIdentity = ConcurrentHashMap<EndpointIdentity, MutableSet<InstanceEndpointKey>>()
    private val endpointHitsByKey = ConcurrentHashMap<InstanceEndpointKey, Long>()
    private val disabledEndpointModulesByName = ConcurrentHashMap<String, DisabledEndpointModule>()

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
     * Blocks until two delta batches, from any instance, have arrived after this call began.
     * Waiting for two, not one, closes two gaps a single [awaitNextFlush] leaves open: a flush
     * can already be mid-compute when the caller's last action completes, so it can land without
     * that action's hits, and the same flush tick sends its manifest and its delta batch
     * concurrently, so a probe a caller just triggered for the first time may not have a manifest
     * entry yet even once its delta batch arrives. A second batch means at least one full tick
     * started after the action, and gives that tick's concurrent manifest send a whole interval
     * to land. A test that needs the manifest entry itself, not just the count, can still call
     * [awaitProbe] or [awaitEndpoint] for that exact guarantee.
     *
     * Throws [TimeoutException] if [timeout] elapses before two batches arrive.
     */
    fun awaitSettled(timeout: Duration) {
        val start = deltaBatchSeq.get()
        awaitUntil(timeout, "fewer than two delta batches arrived within $timeout") { deltaBatchSeq.get() >= start + 2 }
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
     *
     * A probe belonging to a Kotlin inline function, or a branch inside one, is left out: a
     * Kotlin caller copies the body into its own call site instead of invoking it, so a zero hit
     * total is not evidence the code never ran. See ADR 0022.
     */
    fun neverHit(): List<ProbeRef> =
        probesByKey.entries
            .filter { (key, probe) -> !probe.inline && (hitsByKey[key] ?: 0L) <= 0L }
            .map { (key, probe) ->
                ProbeRef(
                    key.serviceInstanceId,
                    probe.className,
                    probe.methodName,
                    probe.methodDescriptor,
                    probe.line,
                    probe.kind,
                    probe.branchIndex,
                    probe.inline,
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
     * never appears in this list either. A declared class whose every declared method is inline
     * is also excluded: Kotlin callers never invoke such a class's methods directly, so it never
     * loading at all is not evidence it is dead. See ADR 0022.
     */
    fun neverLoaded(): List<String> {
        check(completedScans.isNotEmpty()) { "no complete static baseline scan has been received yet" }
        return consultedDeclaredNames.filter { it !in dynamicallyKnownClassNames && it !in consultedAllInlineNames }.sorted()
    }

    /**
     * True if the endpoint identified by [verb] and [routeTemplate] has a summed call count above
     * zero. Both arguments are normalised with [RouteTemplateNormalizer.normalizeVerb] and
     * [RouteTemplateNormalizer.normalize] before lookup, so `wasCalled("get", "/checkout/")` finds
     * the same endpoint a manifest reported as `GET /checkout`.
     *
     * Throws [UnknownEndpointException] if no manifest ever mentioned this endpoint.
     */
    fun wasCalled(
        verb: String,
        routeTemplate: String,
    ): Boolean = callCount(verb, routeTemplate) > 0L

    /**
     * The endpoint's call count, summed across every instance that reported it: each instance's
     * own `hits_total` is already merged with max() against every redelivered value it sent, and
     * this sums one such total per instance, mirroring how [hitCount] aggregates method probes
     * across instances by class and method name. [verb] and [routeTemplate] are normalised the
     * same way [wasCalled] normalises them.
     *
     * Throws [UnknownEndpointException] if no manifest ever mentioned this endpoint.
     */
    fun callCount(
        verb: String,
        routeTemplate: String,
    ): Long {
        val identity = normalizeEndpointIdentity(verb, routeTemplate)
        val keys = endpointKeysByIdentity[identity] ?: throw unknownEndpoint(identity)
        return keys.sumOf { endpointHitsByKey[it] ?: 0L }
    }

    /**
     * Every endpoint at least one instance reported, of any [EndpointDiscoverySource], whose
     * summed call count is zero, sorted by route template then verb. An endpoint discovered by
     * dispatch is by construction called at least once, so it can never appear here.
     */
    fun neverCalled(): List<EndpointRef> =
        endpointRefsByIdentity
            .filterKeys { identity -> (endpointKeysByIdentity[identity]?.sumOf { endpointHitsByKey[it] ?: 0L } ?: 0L) <= 0L }
            .values
            .sortedWith(compareBy({ it.routeTemplate }, { it.verb }))

    /** Every endpoint any instance ever reported, sorted by route template then verb. */
    fun endpoints(): List<EndpointRef> = endpointRefsByIdentity.values.sortedWith(compareBy({ it.routeTemplate }, { it.verb }))

    /** Every endpoint module reported as disabled by any instance, distinct by module name, sorted by module name. */
    fun disabledEndpointModules(): List<DisabledEndpointModule> = disabledEndpointModulesByName.values.sortedBy { it.module }

    /**
     * Blocks until some manifest, from any instance, has mentioned the endpoint identified by
     * [verb] and [routeTemplate], normalised the same way [wasCalled] normalises its arguments.
     * Throws [java.util.concurrent.TimeoutException] if [timeout] elapses first.
     */
    fun awaitEndpoint(
        verb: String,
        routeTemplate: String,
        timeout: Duration,
    ) {
        val identity = normalizeEndpointIdentity(verb, routeTemplate)
        awaitUntil(timeout, "no manifest ever mentioned endpoint $verb $routeTemplate within $timeout") {
            endpointRefsByIdentity.containsKey(identity)
        }
    }

    private fun normalizeEndpointIdentity(
        verb: String,
        routeTemplate: String,
    ): EndpointIdentity = EndpointIdentity(RouteTemplateNormalizer.normalizeVerb(verb), RouteTemplateNormalizer.normalize(routeTemplate))

    private fun unknownEndpoint(identity: EndpointIdentity): UnknownEndpointException {
        val disabled = disabledEndpointModulesByName.values.sortedBy { it.module }
        if (disabled.isNotEmpty()) {
            val listed = disabled.joinToString(", ") { "${it.module}: ${it.reason}" }
            return UnknownEndpointException(
                "${identity.verb} ${identity.routeTemplate}: never mentioned by any manifest; " +
                    "an endpoint module reported itself disabled and may be why: $listed",
            )
        }
        return UnknownEndpointException("${identity.verb} ${identity.routeTemplate}: never mentioned by any manifest")
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
        for (delta in batch.endpointDeltas) {
            val key = InstanceEndpointKey(instanceId, delta.endpointId)
            endpointHitsByKey.merge(key, delta.hitsTotal, ::maxOf)
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
                    location.inline,
                )
            nameIndex.computeIfAbsent(location.className) { ConcurrentHashMap.newKeySet() }.add(key)
            dynamicallyKnownClassNames += location.className
        }
        for (skipped in manifest.skippedClasses) {
            skippedByClassName.putIfAbsent(skipped.className, skipped)
            dynamicallyKnownClassNames += skipped.className
        }
        for (endpointLocation in manifest.endpoints) {
            val identity = EndpointIdentity(endpointLocation.verb, endpointLocation.routeTemplate)
            val key = InstanceEndpointKey(instanceId, endpointLocation.endpointId)
            endpointKeysByIdentity.computeIfAbsent(identity) { ConcurrentHashMap.newKeySet() }.add(key)
            // Upserts unconditionally: a re-delivered record carries a newer handler join or
            // discovery source, and the latest delivery, from any instance, wins.
            endpointRefsByIdentity[identity] =
                EndpointRef(
                    verb = endpointLocation.verb,
                    routeTemplate = endpointLocation.routeTemplate,
                    verbatimTemplate = endpointLocation.verbatimTemplate,
                    framework = endpointLocation.framework,
                    discoverySource = endpointLocation.discoverySource,
                    handlerClass = endpointLocation.handlerClass,
                    handlerMethod = endpointLocation.handlerMethod,
                    handlerDescriptor = endpointLocation.handlerDescriptor,
                )
        }
        for (module in manifest.disabledEndpointModules) {
            disabledEndpointModulesByName.putIfAbsent(module.module, module)
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
        progress.allInlineNames +=
            baseline.declaredClasses.filter { it.methods.isNotEmpty() && it.methods.all { method -> method.inline } }.map { it.className }
        if (!wasComplete && progress.complete) {
            consultedDeclaredNames += progress.declaredNames
            consultedAllInlineNames += progress.allInlineNames
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
            startDaemon(httpServer)
            return collector
        }

        /**
         * A Java thread inherits daemon status from the thread that creates it, and
         * `HttpServer.start()` creates its dispatcher thread on whichever thread calls it. A
         * JUnit extension that called `httpServer.start()` directly would leave that dispatcher
         * thread non-daemon, which alone would keep the JVM from exiting even after every other
         * thread finished. Starting the server from a short-lived daemon thread, and joining it
         * before returning, makes the dispatcher thread daemon too.
         */
        private fun startDaemon(httpServer: HttpServer) {
            val starter = Thread({ httpServer.start() }, "yukon-testkit-http-starter").apply { isDaemon = true }
            starter.start()
            starter.join()
        }
    }
}

/**
 * One probe's identity and location, as reported by a manifest. See [YukonTestCollector] for the
 * class name format. [inline] marks a Kotlin inline function, or a branch inside one; see ADR
 * 0022.
 */
data class ProbeRef(
    val serviceInstanceId: String,
    val className: String,
    val methodName: String,
    val methodDescriptor: String,
    val line: Int,
    val kind: ProbeKind,
    val branchIndex: Int?,
    val inline: Boolean = false,
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

/**
 * One endpoint's identity and display fields, as reported by a manifest. [verb] and
 * [routeTemplate] are the normalised identity; [verbatimTemplate] keeps the framework's own
 * spelling for display. [handlerClass], [handlerMethod], and [handlerDescriptor] are null until a
 * framework hook joins a handler to the endpoint; see CONTEXT.md, "Handler".
 */
data class EndpointRef(
    val verb: String,
    val routeTemplate: String,
    val verbatimTemplate: String,
    val framework: String,
    val discoverySource: EndpointDiscoverySource,
    val handlerClass: String?,
    val handlerMethod: String?,
    val handlerDescriptor: String?,
)

/**
 * Thrown when a query names a `(verb, route template)` [YukonTestCollector] has no endpoint for,
 * instead of reading as "confirmed never called". The message names one of two cases, checked in
 * this order:
 *
 * 1. At least one endpoint module reported itself disabled. The message names every disabled
 *    module and its reason, since the unmentioned endpoint may belong to one of them.
 * 2. No manifest, from any instance, ever mentioned this endpoint.
 */
class UnknownEndpointException(
    message: String,
) : RuntimeException(message)
