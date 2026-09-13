package io.github.lukedevops.yukon.export

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.registry.ProbeRegistry
import java.lang.System.Logger.Level
import java.time.Duration
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
    private val exporter: Exporter,
    /** Source of the first-flush jitter. Injectable so a test can pin the initial delay to zero. */
    private val random: Random = Random.Default,
) {
    private val log = System.getLogger(ExportScheduler::class.java.name)
    private var executor: ScheduledExecutorService? = null

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

    fun stop() {
        executor?.shutdown()
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
        val manifestThread = Thread(::sendManifestDelta, "yukon-export-manifest").apply { isDaemon = true }
        val deltaThread = Thread(::sendDeltaBatch, "yukon-export-delta").apply { isDaemon = true }
        manifestThread.start()
        deltaThread.start()
        manifestThread.join()
        deltaThread.join()
    }

    private fun sendDeltaBatch() {
        try {
            val snapshot = registry.computeDeltaBatch(resourceAttributes())
            exporter.exportDeltaBatch(snapshot.batch)
            registry.advanceBaseline(snapshot)
        } catch (e: Exception) {
            log.log(Level.WARNING, "yukon: delta export failed, will retry next flush", e)
        }
    }

    /**
     * Sends only the probes not yet included in a successfully delivered
     * manifest.
     *
     * This runs on the first flush, not at agent startup. By the first
     * flush, classes have actually started loading, so there are probes to
     * send. Classes that register later (lazy singletons, or a code path
     * run for the first time) are still picked up on a later flush. They
     * are not permanently left out just because an earlier send already
     * succeeded.
     */
    private fun sendManifestDelta() {
        try {
            val snapshot = registry.computeManifestDelta(config.serviceName, config.serviceVersion, config.serviceInstanceId)
            val manifest = snapshot.manifest
            if (manifest.probes.isEmpty() && manifest.skippedClasses.isEmpty()) return
            exporter.exportManifest(manifest)
            registry.advanceManifestBaseline(snapshot)
        } catch (e: Exception) {
            log.log(Level.WARNING, "yukon: manifest export failed, will retry next flush", e)
        }
    }

    private fun resourceAttributes() =
        ResourceAttributes(
            serviceName = config.serviceName,
            serviceVersion = config.serviceVersion,
            serviceInstanceId = config.serviceInstanceId,
            environment = config.environment,
        )
}
