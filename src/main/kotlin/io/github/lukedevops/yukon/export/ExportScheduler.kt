package io.github.lukedevops.yukon.export

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.registry.ProbeRegistry
import java.lang.System.Logger.Level
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Flushes on a fixed interval, with each instance picking a random offset
 * within that interval before its first flush. Otherwise a fleet of
 * instances started around the same time would all flush on the same
 * wall-clock tick and thunder-herd the collector. Uses
 * `java.lang.System.Logger` rather than a logging framework dependency, to
 * avoid pulling anything onto the target app's classpath for an
 * agent-internal warning.
 */
class ExportScheduler(
    private val config: AgentConfig,
    private val registry: ProbeRegistry,
    private val exporter: Exporter,
) {
    private val log = System.getLogger(ExportScheduler::class.java.name)
    private var executor: ScheduledExecutorService? = null

    @Volatile private var manifestSent = false

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
     * One flush attempt. Sends the probe manifest first if it hasn't gone out
     * yet. That's deferred to the first flush rather than sent at agent
     * startup, so it actually has probes in it once classes have started
     * loading. Then it computes the delta since the last acknowledged
     * baseline and sends it, only advancing the baseline on success. Neither
     * send has an explicit retry queue: a failure simply leaves the relevant
     * state where it is, so the next tick naturally retries.
     */
    fun flush() {
        if (!manifestSent) sendManifest()

        val batch = registry.computeDeltaBatch(resourceAttributes())
        if (batch.deltas.isEmpty()) return
        try {
            exporter.exportDeltaBatch(batch)
            registry.advanceBaseline()
        } catch (e: Exception) {
            log.log(Level.WARNING, "yukon: delta export failed, will retry next flush", e)
        }
    }

    private fun sendManifest() {
        try {
            exporter.exportManifest(registry.manifest(config.serviceName, config.serviceVersion))
            manifestSent = true
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
