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
     * yet, then the delta since the last acknowledged baseline. The delta
     * batch is sent even when empty: with no signal otherwise, a collector
     * can't tell an instance that's alive but genuinely idle from one that's
     * crashed or lost its network path, so an empty batch doubles as a
     * liveness heartbeat. Neither send has an explicit retry queue: a
     * failure simply leaves the relevant state where it is, so the next tick
     * naturally retries.
     */
    fun flush() {
        sendManifestDelta()

        val batch = registry.computeDeltaBatch(resourceAttributes())
        try {
            exporter.exportDeltaBatch(batch)
            registry.advanceBaseline()
        } catch (e: Exception) {
            log.log(Level.WARNING, "yukon: delta export failed, will retry next flush", e)
        }
    }

    /**
     * Sends only the probes not yet included in a successfully delivered
     * manifest, deferred to the first flush rather than agent startup so it
     * actually has probes in it once classes have started loading. Classes
     * that register later in the process's life (lazy singletons, a code
     * path exercised for the first time) get picked up here too, rather than
     * being permanently absent from every manifest because an earlier send
     * already succeeded.
     */
    private fun sendManifestDelta() {
        val manifest = registry.computeManifestDelta(config.serviceName, config.serviceVersion)
        if (manifest.probes.isEmpty() && manifest.skippedClasses.isEmpty()) return
        try {
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
