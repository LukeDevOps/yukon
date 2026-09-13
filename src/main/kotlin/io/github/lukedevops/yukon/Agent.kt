package io.github.lukedevops.yukon

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ExportScheduler
import io.github.lukedevops.yukon.export.Exporter
import io.github.lukedevops.yukon.export.HttpOtlpStyleExporter
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.instrumentation.BootstrapInstallException
import io.github.lukedevops.yukon.instrumentation.YukonInstrumentation
import io.github.lukedevops.yukon.instrumentation.staticscan.StaticBaselineMismatchDetector
import io.github.lukedevops.yukon.instrumentation.staticscan.StaticBaselinePublisher
import io.github.lukedevops.yukon.instrumentation.staticscan.StaticBaselineScanner
import io.github.lukedevops.yukon.registry.ProbeRegistry
import java.lang.System.Logger.Level
import java.lang.instrument.Instrumentation
import java.time.Duration

/** `-javaagent:yukon-agent.jar` entry point. */
object Agent {
    private val SHUTDOWN_FLUSH_TIMEOUT: Duration = Duration.ofSeconds(10)
    private val log = System.getLogger(Agent::class.java.name)

    @JvmStatic
    fun premain(
        agentArgs: String?,
        instrumentation: Instrumentation,
    ) {
        val config = AgentConfig.parse(agentArgs)
        val registry = ProbeRegistry()
        val staticBaselineMismatchDetector = StaticBaselineMismatchDetector()

        try {
            YukonInstrumentation(config, registry, staticBaselineMismatchDetector).install(instrumentation)
        } catch (e: BootstrapInstallException) {
            // Nothing is instrumented and nothing is exported. A missing instance is a visible
            // signal at the collector; an instance reporting zero hits everywhere would not be.
            log.log(Level.ERROR, "yukon: could not install the bootstrap holder; the agent is disabled for this JVM", e)
            return
        }

        val exporter = HttpOtlpStyleExporter(config.collectorEndpoint)
        val scheduler = ExportScheduler(config, registry, exporter)
        scheduler.start()

        if (config.staticBaselineEnabled) {
            startStaticBaselineScan(config, exporter, registry, staticBaselineMismatchDetector)
        }

        Runtime.getRuntime().addShutdownHook(
            Thread({ scheduler.flushOnShutdown(SHUTDOWN_FLUSH_TIMEOUT) }, "yukon-shutdown-hook"),
        )
    }

    /**
     * Runs on its own background thread, off `premain`, so a full classpath walk never adds
     * latency to the target app's startup. Fires once per process: no periodic re-scan, matching
     * "static" in the name. See [StaticBaselinePublisher] for what happens once the scan is done.
     */
    private fun startStaticBaselineScan(
        config: AgentConfig,
        exporter: Exporter,
        registry: ProbeRegistry,
        mismatchDetector: StaticBaselineMismatchDetector,
    ) {
        val scanner = StaticBaselineScanner(config.instrumentedPackagePrefixes)
        val publisher = StaticBaselinePublisher(scanner::scan, exporter, registry, mismatchDetector)
        val resource =
            ResourceAttributes(
                config.serviceName,
                config.serviceVersion,
                config.serviceInstanceId,
                config.environment,
            )
        val worker = Thread({ publisher.run(resource) }, "yukon-static-baseline-scan")
        worker.isDaemon = true
        worker.start()
    }
}
