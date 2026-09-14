package io.github.lukedevops.yukon.export

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.registry.EndpointRegistry
import io.github.lukedevops.yukon.registry.ProbeRegistry
import java.lang.System.Logger.Level
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Flushes on a fixed interval. Each instance picks a random offset within
 * that interval before its first flush. Without this, a fleet of instances
 * started around the same time would all flush on the same wall-clock tick,
 * overwhelming the collector at once.
 *
 * Logs through `java.lang.System.Logger`, not a logging framework. This
 * keeps agent-internal warnings off the target app's classpath.
 */
class ExportScheduler(
    private val config: AgentConfig,
    private val registry: ProbeRegistry,
    /** Endpoint hit and manifest state; see [EndpointRegistry] and ADR 0017. */
    private val endpointRegistry: EndpointRegistry,
    private val exporter: Exporter,
    /** Source of the first-flush jitter. Injectable so a test can pin the initial delay to zero. */
    private val random: Random = Random.Default,
    /** Upper bound on changed probes per delta POST; see [ProbeRegistry.computeDeltaBatches]. */
    private val maxDeltasPerBatch: Int = DEFAULT_MAX_DELTAS_PER_BATCH,
    /** Upper bound on probe locations plus skipped classes per manifest POST; see [ProbeRegistry.computeManifestDeltas]. */
    private val maxManifestEntriesPerChunk: Int = DEFAULT_MAX_MANIFEST_ENTRIES_PER_CHUNK,
) {
    private val log = System.getLogger(ExportScheduler::class.java.name)
    private var executor: ScheduledExecutorService? = null

    /** Runs the two sends of each flush side by side; see [flush]. Two threads, created once, not two per tick. */
    private val sendPool: ExecutorService =
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "yukon-export-send").apply { isDaemon = true }
        }

    fun start() {
        val executor =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "yukon-export").apply { isDaemon = true }
            }
        this.executor = executor
        val intervalMillis = config.flushInterval.toMillis()
        val initialDelayMillis = if (intervalMillis > 0) random.nextLong(intervalMillis) else 0L
        executor.scheduleAtFixedRate(::flush, initialDelayMillis, intervalMillis, TimeUnit.MILLISECONDS)
    }

    /** Stops the schedule and the send pool. [flush] must not be called after this. */
    fun stop() {
        executor?.shutdown()
        sendPool.shutdown()
    }

    /**
     * Best-effort final flush for a graceful JVM exit, bounded by [budget] in total.
     *
     * The scheduled executor is stopped first, and any flush already in flight on it is allowed
     * to finish before the final one starts. Two flushes never run at the same time this way:
     * each holds its own [ProbeRegistry.DeltaSnapshot], so overlap would be safe, but it would
     * also mean two concurrent POSTs racing to the collector for no benefit.
     *
     * [budget] is shared between waiting for the in-flight flush and running the final one. If
     * the in-flight flush is deep in retries against an unreachable collector and uses it all
     * up, the final flush is skipped. That in-flight flush already carried the latest snapshot
     * it could take, so what is lost is bounded by the hits since it started, and shutdown never
     * stretches past what an orchestrator's termination grace period allows.
     */
    fun flushOnShutdown(budget: Duration) {
        val deadlineNanos = System.nanoTime() + budget.toNanos()
        val executor = this.executor
        if (executor != null) {
            executor.shutdown()
            if (!executor.awaitTermination(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS)) {
                log.log(Level.WARNING, "yukon: an in-flight flush used up the shutdown budget; skipping the final flush")
                return
            }
        }
        val worker = Thread(::flush, "yukon-shutdown-flush").apply { isDaemon = true }
        worker.start()
        worker.join(remainingMillis(deadlineNanos))
        sendPool.shutdown()
    }

    private fun remainingMillis(deadlineNanos: Long): Long = maxOf(0L, (deadlineNanos - System.nanoTime()) / 1_000_000)

    /**
     * One flush attempt. Sends whatever manifest entries haven't gone out
     * yet, and the delta since the last acknowledged baseline.
     *
     * The delta batch is sent even when empty. A collector otherwise has no
     * way to tell an instance that's alive but idle from one that's crashed
     * or lost its network path. An empty batch acts as a liveness heartbeat.
     *
     * Neither send has an explicit retry queue. A failure just leaves the
     * relevant state where it is, so the next tick retries it naturally.
     * [io.github.lukedevops.yukon.export.HttpOtlpStyleExporter] still wraps
     * each individual send in its own capped exponential backoff, for a
     * transient failure within one attempt.
     *
     * The two sends run concurrently, not one after the other. Each can take
     * a while to fail on its own (multiple retries, each with its own
     * timeout) if the collector is unreachable. Run back to back, a single
     * flush's worst case could take roughly twice one flush interval,
     * meaning the liveness heartbeat above would arrive far less often than
     * configured during exactly the outage it exists to report. Running
     * them side by side keeps one flush's worst case close to a single
     * send's worst case instead of the sum of both.
     *
     * This method runs under `scheduleAtFixedRate`, which stops calling a
     * task forever the first time it lets an exception escape, with nothing
     * logged. So `sendManifestDelta` and `sendDeltaBatch` each wrap their
     * own registry call (`compute*`) and exporter call in one try/catch: a
     * registry exception must never escape either one, or every future
     * flush, including the heartbeat, silently stops.
     */
    fun flush() {
        val manifestSend = sendPool.submit(::sendManifestDelta)
        val deltaSend = sendPool.submit(::sendDeltaBatch)
        manifestSend.get()
        deltaSend.get()
    }

    /**
     * One outgoing [DeltaBatch], together with the probe and endpoint snapshots it carries. A
     * confirmed send advances exactly these, and nothing else.
     */
    private class DeltaSend(
        val batch: DeltaBatch,
        val probeSnapshot: ProbeRegistry.DeltaSnapshot?,
        val endpointSnapshots: List<EndpointRegistry.DeltaSnapshot>,
    )

    /**
     * Sends probe and endpoint deltas together, advancing each snapshot only once its send is
     * confirmed. A failure stops the loop: the sends already confirmed stay advanced, the rest
     * are recomputed and resent on the next flush.
     */
    private fun sendDeltaBatch() {
        try {
            val resource = resourceAttributes()
            val probeBatches = registry.computeDeltaBatches(resource, maxDeltasPerBatch)
            val endpointBatches = endpointRegistry.computeDeltas(maxDeltasPerBatch)
            for (send in composeDeltaSends(resource, probeBatches, endpointBatches)) {
                exporter.exportDeltaBatch(send.batch)
                send.probeSnapshot?.let(registry::advanceBaseline)
                send.endpointSnapshots.forEach(endpointRegistry::advanceDeltas)
            }
        } catch (e: Exception) {
            log.log(Level.WARNING, "yukon: delta export failed, will retry next flush", e)
        }
    }

    /**
     * Packs endpoint delta snapshots onto probe delta batches. Each endpoint snapshot goes on the
     * first probe batch with room for it, room being [maxDeltasPerBatch] minus the probe deltas
     * and any endpoint snapshot already packed onto that batch; a snapshot that fits nowhere
     * becomes its own [DeltaBatch] with no probe deltas.
     *
     * [probeBatches] is never empty: [ProbeRegistry.computeDeltaBatches] always returns at least
     * one batch as the liveness heartbeat. That batch is where every endpoint snapshot lands when
     * nothing else changed, so a flush with only endpoint activity still sends exactly one
     * [DeltaBatch], carrying both the heartbeat and the endpoint deltas.
     */
    private fun composeDeltaSends(
        resource: ResourceAttributes,
        probeBatches: List<ProbeRegistry.DeltaSnapshot>,
        endpointBatches: List<EndpointRegistry.DeltaSnapshot>,
    ): List<DeltaSend> {
        val builders = probeBatches.map { DeltaSendBuilder(it.batch, it) }.toMutableList()
        val standalone = mutableListOf<DeltaSendBuilder>()

        for (endpointSnapshot in endpointBatches) {
            val target = builders.firstOrNull { it.size + endpointSnapshot.deltas.size <= maxDeltasPerBatch }
            if (target != null) {
                target.batch = target.batch.copy(endpointDeltas = target.batch.endpointDeltas + endpointSnapshot.deltas)
                target.size += endpointSnapshot.deltas.size
                target.endpointSnapshots += endpointSnapshot
            } else {
                standalone +=
                    DeltaSendBuilder(DeltaBatch(resource, emptyList(), endpointSnapshot.deltas), null).apply {
                        endpointSnapshots += endpointSnapshot
                    }
            }
        }
        return (builders + standalone).map { DeltaSend(it.batch, it.probeSnapshot, it.endpointSnapshots) }
    }

    private class DeltaSendBuilder(
        var batch: DeltaBatch,
        val probeSnapshot: ProbeRegistry.DeltaSnapshot?,
    ) {
        var size = batch.deltas.size
        val endpointSnapshots = mutableListOf<EndpointRegistry.DeltaSnapshot>()
    }

    /**
     * One outgoing [ProbeManifest], together with the probe and endpoint snapshots it carries. A
     * confirmed send advances exactly these, and nothing else.
     */
    private class ManifestSend(
        val manifest: ProbeManifest,
        val probeSnapshot: ProbeRegistry.ManifestSnapshot?,
        val endpointSnapshots: List<EndpointRegistry.ManifestSnapshot>,
    )

    /**
     * Sends only the probes and endpoints not yet included in a successfully delivered manifest.
     *
     * This runs on the first flush, not at agent startup. By the first
     * flush, classes have actually started loading, so there are probes to
     * send. Classes that register later (lazy singletons, or a code path
     * run for the first time) are still picked up on a later flush. They
     * are not permanently left out just because an earlier send already
     * succeeded. The same holds for endpoints: a discovery-source upgrade
     * or a handler join learned after an earlier delivery is picked up the
     * same way.
     */
    private fun sendManifestDelta() {
        try {
            val classChunks =
                registry.computeManifestDeltas(
                    config.serviceName,
                    config.serviceVersion,
                    config.serviceInstanceId,
                    maxManifestEntriesPerChunk,
                )
            val endpointChunks = endpointRegistry.computeManifestEntries(maxManifestEntriesPerChunk)
            for (send in composeManifestSends(classChunks, endpointChunks)) {
                exporter.exportManifest(send.manifest)
                send.probeSnapshot?.let(registry::advanceManifestBaseline)
                send.endpointSnapshots.forEach(endpointRegistry::advanceManifest)
            }
        } catch (e: Exception) {
            log.log(Level.WARNING, "yukon: manifest export failed, will retry next flush", e)
        }
    }

    /**
     * Packs endpoint manifest chunks onto class manifest chunks. Each endpoint chunk goes on the
     * first class chunk with room for it, room being [maxManifestEntriesPerChunk] minus probes,
     * skipped classes, and any endpoint chunk already packed onto that chunk. A chunk that fits
     * nowhere, including when there are no class chunks at all, becomes its own [ProbeManifest]
     * with no probes or skipped classes.
     */
    private fun composeManifestSends(
        classChunks: List<ProbeRegistry.ManifestSnapshot>,
        endpointChunks: List<EndpointRegistry.ManifestSnapshot>,
    ): List<ManifestSend> {
        val builders = classChunks.map { ManifestSendBuilder(it.manifest, it) }.toMutableList()
        val standalone = mutableListOf<ManifestSendBuilder>()

        for (endpointChunk in endpointChunks) {
            val chunkSize = endpointChunk.endpoints.size + endpointChunk.disabledModules.size
            val target = builders.firstOrNull { it.size + chunkSize <= maxManifestEntriesPerChunk }
            if (target != null) {
                target.manifest =
                    target.manifest.copy(
                        endpoints = target.manifest.endpoints + endpointChunk.endpoints,
                        disabledEndpointModules = target.manifest.disabledEndpointModules + endpointChunk.disabledModules,
                    )
                target.size += chunkSize
                target.endpointSnapshots += endpointChunk
            } else {
                standalone +=
                    ManifestSendBuilder(standaloneEndpointManifest(endpointChunk), null).apply {
                        endpointSnapshots += endpointChunk
                    }
            }
        }
        return (builders + standalone).map { ManifestSend(it.manifest, it.probeSnapshot, it.endpointSnapshots) }
    }

    private fun standaloneEndpointManifest(endpointChunk: EndpointRegistry.ManifestSnapshot): ProbeManifest =
        ProbeManifest(
            serviceName = config.serviceName,
            serviceVersion = config.serviceVersion,
            probes = emptyList(),
            skippedClasses = emptyList(),
            serviceInstanceId = config.serviceInstanceId,
            endpoints = endpointChunk.endpoints,
            disabledEndpointModules = endpointChunk.disabledModules,
        )

    private class ManifestSendBuilder(
        var manifest: ProbeManifest,
        val probeSnapshot: ProbeRegistry.ManifestSnapshot?,
    ) {
        var size = manifest.probes.size + manifest.skippedClasses.size + manifest.endpoints.size + manifest.disabledEndpointModules.size
        val endpointSnapshots = mutableListOf<EndpointRegistry.ManifestSnapshot>()
    }

    private fun resourceAttributes() =
        ResourceAttributes(
            serviceName = config.serviceName,
            serviceVersion = config.serviceVersion,
            serviceInstanceId = config.serviceInstanceId,
            environment = config.environment,
        )

    companion object {
        /** A delta is a few dozen bytes on the wire, so this is well under a megabyte per POST. */
        const val DEFAULT_MAX_DELTAS_PER_BATCH: Int = 20_000

        /** A probe location carries class and method strings, so it is roughly ten times a delta's size. */
        const val DEFAULT_MAX_MANIFEST_ENTRIES_PER_CHUNK: Int = 5_000
    }
}
