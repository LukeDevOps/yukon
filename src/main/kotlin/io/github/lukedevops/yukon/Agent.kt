package io.github.lukedevops.yukon

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ExportScheduler
import io.github.lukedevops.yukon.export.HttpOtlpStyleExporter
import io.github.lukedevops.yukon.instrumentation.YukonInstrumentation
import io.github.lukedevops.yukon.registry.ProbeRegistry
import java.lang.instrument.Instrumentation
import java.time.Duration

/** `-javaagent:yukon-agent.jar` entry point. */
object Agent {
    private val SHUTDOWN_FLUSH_TIMEOUT: Duration = Duration.ofSeconds(10)

    @JvmStatic
    fun premain(
        agentArgs: String?,
        instrumentation: Instrumentation,
    ) {
        val config = AgentConfig.parse(agentArgs)
        val registry = ProbeRegistry()

        YukonInstrumentation(config, registry).install(instrumentation)

        val exporter = HttpOtlpStyleExporter(config.collectorEndpoint)
        val scheduler = ExportScheduler(config, registry, exporter)
        scheduler.start()

        Runtime.getRuntime().addShutdownHook(Thread({ flushOnShutdown(scheduler) }, "yukon-shutdown-flush"))
    }

    /**
     * Best-effort final flush on a graceful JVM exit.
     *
     * Without this, a normal shutdown loses up to one flush interval of hit data, not just a
     * crash: [ExportScheduler] only flushes on its fixed schedule, and its executor thread is a
     * daemon that does not delay exit on its own.
     *
     * Bounded by [SHUTDOWN_FLUSH_TIMEOUT] so an unreachable collector cannot stretch shutdown out
     * past what an orchestrator's termination grace period allows. [ExportScheduler.flush] already
     * retries each send with its own capped backoff; this only bounds how long the shutdown hook
     * itself waits on that call, not the retries within it.
     */
    private fun flushOnShutdown(scheduler: ExportScheduler) {
        val worker = Thread(scheduler::flush, "yukon-shutdown-flush-worker").apply { isDaemon = true }
        worker.start()
        worker.join(SHUTDOWN_FLUSH_TIMEOUT.toMillis())
    }
}
