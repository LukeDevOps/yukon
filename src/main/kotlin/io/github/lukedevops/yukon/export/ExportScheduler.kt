package io.github.lukedevops.yukon.export

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.registry.ProbeRegistry
import java.lang.System.Logger.Level
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
        val initialDelayMillis = if (intervalMillis > 0) Random.nextLong(intervalMillis) else 0L
        executor.scheduleAtFixedRate(::flush, initialDelayMillis, intervalMillis, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        executor?.shutdown()
    }

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
            val batch = registry.computeDeltaBatch(resourceAttributes())
            exporter.exportDeltaBatch(batch)
            registry.advanceBaseline()
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
            val manifest = registry.computeManifestDelta(config.serviceName, config.serviceVersion)
            if (manifest.probes.isEmpty() && manifest.skippedClasses.isEmpty()) return
            exporter.exportManifest(manifest)
            registry.advanceManifestBaseline()
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
