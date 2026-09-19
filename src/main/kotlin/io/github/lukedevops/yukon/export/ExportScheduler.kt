package io.github.lukedevops.yukon.export

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.instrumentation.UnreportedClassSweep
import io.github.lukedevops.yukon.instrumentation.branch.BranchDropCounts
import io.github.lukedevops.yukon.instrumentation.branch.BranchDropReason
import io.github.lukedevops.yukon.registry.EndpointRegistry
import io.github.lukedevops.yukon.registry.ProbeRegistry
import java.lang.System.Logger.Level
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    /** Dropped branch site totals; see [maybeLogBranchDrops] and ADR 0025. */
    private val branchDropCounts: BranchDropCounts = BranchDropCounts(),
    /**
     * Finds classes that loaded but reached no transformer; see [maybeSweep] and ADR 0027. Null
     * when nothing supplied one, which is every test that does not exercise the sweep.
     */
    private val unreportedClassSweep: UnreportedClassSweep? = null,
) {
    private val log = System.getLogger(ExportScheduler::class.java.name)
    private var executor: ScheduledExecutorService? = null
    private val branchDropsLogged = AtomicBoolean(false)
    private var flushesSinceSweep = SWEEP_EVERY_N_FLUSHES

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
        // Thread.join(0) waits with no limit, so a budget that ran out while waiting above (or
        // one that was zero to begin with) must skip the final flush rather than start it.
        val remaining = remainingMillis(deadlineNanos)
        if (remaining <= 0L) {
            log.log(Level.WARNING, "yukon: no shutdown budget left for the final flush; skipping it")
            return
        }
        val worker = Thread({ flush(final = true) }, "yukon-shutdown-flush").apply { isDaemon = true }
        worker.start()
        worker.join(remaining)
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
     * task forever the first time it lets a throwable escape, with nothing
     * logged. So `sendManifestDelta` and `sendDeltaBatch` each wrap their
     * own registry call (`compute*`) and exporter call in one catch of
     * `Throwable`, not `Exception`: a `NoClassDefFoundError` from a class
     * first touched on the export path is as fatal to the schedule as any
     * exception, and would otherwise stop every future flush, heartbeat
     * included. The outer catch here covers what the sends cannot, such as
     * a rejected submission after [stop].
     *
     * [final] is true only for the flush [flushOnShutdown] runs. It is carried onto every delta
     * batch this flush sends, the empty heartbeat and an endpoint-only standalone batch included,
     * so a collector can tell an instance that ended cleanly from one that went silent. The
     * manifest send is unaffected. See ADR 0010.
     */
    fun flush(final: Boolean = false) {
        try {
            maybeLogBranchDrops()
            maybeSweep(final)
            val manifestSend = sendPool.submit(::sendManifestDelta)
            val deltaSend = sendPool.submit { sendDeltaBatch(final) }
            manifestSend.get()
            deltaSend.get()
        } catch (t: Throwable) {
            log.log(Level.ERROR, "yukon: flush failed outside its own send guards, will retry next flush", t)
        }
    }

    /**
     * Runs the unreported-class sweep before this flush's sends, so what it finds goes out on the
     * same manifest rather than waiting a whole cycle.
     *
     * Not on every flush. A class that loaded unreported stays unreported, so the finding has no
     * expiry, while the sweep walks every class the JVM holds and allocates an array of them. The
     * shutdown flush always sweeps, so an instance that ends cleanly always gives a final answer.
     *
     * Guarded like the sends are: this runs under `scheduleAtFixedRate`, which stops calling a
     * task forever the first time one lets a throwable escape.
     */
    private fun maybeSweep(final: Boolean) {
        val sweep = unreportedClassSweep ?: return
        if (!final && --flushesSinceSweep > 0) return
        flushesSinceSweep = SWEEP_EVERY_N_FLUSHES
        try {
            sweep.run()
        } catch (t: Throwable) {
            log.log(Level.WARNING, "yukon: the unreported-class sweep failed, will retry on a later flush", t)
        }
    }

    /**
     * Logs one INFO line naming the branch sites dropped so far, the first time a flush finds the
     * total above zero. Nothing is logged on a flush that finds no drops yet, and nothing is
     * logged again once it has. See [BranchDropCounts] and ADR 0025.
     */
    private fun maybeLogBranchDrops() {
        if (branchDropsLogged.get()) return
        val total = branchDropCounts.total()
        if (total <= 0) return
        if (branchDropsLogged.compareAndSet(false, true)) {
            val inlinedOutOfScope = branchDropCounts.countOf(BranchDropReason.INLINED_OUT_OF_SCOPE)
            val coroutineMachinery = branchDropCounts.countOf(BranchDropReason.COROUTINE_MACHINERY)
            log.log(
                Level.INFO,
                "yukon: left $total branch sites in ${branchDropCounts.classesWithDrops()} classes without a probe: " +
                    "$inlinedOutOfScope inlined from out-of-scope code, $coroutineMachinery coroutine machinery",
            )
        }
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
    private fun sendDeltaBatch(final: Boolean) {
        try {
            val resource = resourceAttributes()
            val probeBatches = registry.computeDeltaBatches(resource, maxDeltasPerBatch)
            val endpointBatches = endpointRegistry.computeDeltas(maxDeltasPerBatch)
            for (send in composeDeltaSends(resource, probeBatches, endpointBatches, final)) {
                exporter.exportDeltaBatch(send.batch)
                send.probeSnapshot?.let(registry::advanceBaseline)
                send.endpointSnapshots.forEach(endpointRegistry::advanceDeltas)
            }
        } catch (t: Throwable) {
            log.log(Level.WARNING, "yukon: delta export failed, will retry next flush", t)
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
     *
     * [final] is stamped onto every batch built here, including the heartbeat and a standalone
     * endpoint-only batch, so a shutdown flush with nothing to report still tells the collector
     * this instance ended cleanly.
     */
    private fun composeDeltaSends(
        resource: ResourceAttributes,
        probeBatches: List<ProbeRegistry.DeltaSnapshot>,
        endpointBatches: List<EndpointRegistry.DeltaSnapshot>,
        final: Boolean,
    ): List<DeltaSend> {
        val builders = probeBatches.map { DeltaSendBuilder(it.batch.copy(finalFlush = final), it) }.toMutableList()
        val standalone = mutableListOf<DeltaSendBuilder>()

        for (endpointSnapshot in endpointBatches) {
            val target = builders.firstOrNull { it.size + endpointSnapshot.deltas.size <= maxDeltasPerBatch }
            if (target != null) {
                target.batch = target.batch.copy(endpointDeltas = target.batch.endpointDeltas + endpointSnapshot.deltas)
                target.size += endpointSnapshot.deltas.size
                target.endpointSnapshots += endpointSnapshot
            } else {
                standalone +=
                    DeltaSendBuilder(DeltaBatch(resource, emptyList(), endpointSnapshot.deltas, finalFlush = final), null).apply {
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
        } catch (t: Throwable) {
            log.log(Level.WARNING, "yukon: manifest export failed, will retry next flush", t)
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

        /**
         * How many flushes pass between sweeps, roughly five minutes at the default interval. Not
         * an option: nobody can pick a better number without knowing what the walk costs on their
         * own application, and the finding it produces does not go stale.
         */
        const val SWEEP_EVERY_N_FLUSHES: Int = 10
    }
}
