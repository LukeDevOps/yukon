package io.github.lukedevops.yukon

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ExportScheduler
import io.github.lukedevops.yukon.export.Exporter
import io.github.lukedevops.yukon.export.HttpOtlpStyleExporter
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.export.StaticBaseline
import io.github.lukedevops.yukon.instrumentation.YukonInstrumentation
import io.github.lukedevops.yukon.instrumentation.staticscan.StaticBaselineMismatchDetector
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

        YukonInstrumentation(config, registry, staticBaselineMismatchDetector).install(instrumentation)

        val exporter = HttpOtlpStyleExporter(config.collectorEndpoint)
        val scheduler = ExportScheduler(config, registry, exporter)
        scheduler.start()

        if (config.staticBaselineEnabled) {
            startStaticBaselineScan(config, exporter, staticBaselineMismatchDetector)
        }

        Runtime.getRuntime().addShutdownHook(
            Thread({ scheduler.flushOnShutdown(SHUTDOWN_FLUSH_TIMEOUT) }, "yukon-shutdown-hook"),
        )
    }

    /**
     * Runs on its own background thread, off `premain`, so a full classpath walk never adds
     * latency to the target app's startup. Fires once per process: no periodic re-scan, matching
     * "static" in the name. A failed send is not retried beyond [HttpOtlpStyleExporter]'s own
     * backoff, since there is no next flush to naturally retry it on, unlike the delta batch and
     * manifest.
     */
    private fun startStaticBaselineScan(
        config: AgentConfig,
        exporter: Exporter,
        mismatchDetector: StaticBaselineMismatchDetector,
    ) {
        val worker =
            Thread({
                val result = StaticBaselineScanner(config.instrumentedPackagePrefixes).scan()
                mismatchDetector.knownDeclaredClassNames = result.declaredClasses.map { it.className }.toSet()
                val baseline =
                    StaticBaseline(
                        resource =
                            ResourceAttributes(
                                config.serviceName,
                                config.serviceVersion,
                                config.serviceInstanceId,
                                config.environment,
                            ),
                        declaredClasses = result.declaredClasses,
                        staticallyUnsafeClasses = result.staticallyUnsafeClasses,
                        unreadableClasses = result.unreadableClasses,
                        scannedAt = System.currentTimeMillis(),
                    )
                try {
                    exporter.exportStaticBaseline(baseline)
                } catch (e: Exception) {
                    log.log(
                        Level.WARNING,
                        "yukon: failed to send the static baseline; it will not be retried until the next process start",
                        e,
                    )
                }
            }, "yukon-static-baseline-scan")
        worker.isDaemon = true
        worker.start()
    }
}
