package io.github.lukedevops.yukon

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ExportScheduler
import io.github.lukedevops.yukon.export.Exporter
import io.github.lukedevops.yukon.export.HttpOtlpStyleExporter
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.instrumentation.BootstrapInstallException
import io.github.lukedevops.yukon.instrumentation.YukonInstrumentation
import io.github.lukedevops.yukon.instrumentation.endpoints.EndpointInstrumentation
import io.github.lukedevops.yukon.instrumentation.endpoints.EndpointModules
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import io.github.lukedevops.yukon.instrumentation.staticscan.StaticBaselineMismatchDetector
import io.github.lukedevops.yukon.instrumentation.staticscan.StaticBaselinePublisher
import io.github.lukedevops.yukon.instrumentation.staticscan.StaticBaselineScanner
import io.github.lukedevops.yukon.registry.EndpointRegistry
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.lang.System.Logger.Level
import java.lang.instrument.Instrumentation
import java.time.Duration

/** `-javaagent:yukon-agent.jar` entry point. */
object Agent {
    private val SHUTDOWN_FLUSH_TIMEOUT: Duration = Duration.ofSeconds(10)
    private const val OTEL_BRIDGE_MODULE_NAME = "otel"
    private val log = System.getLogger(Agent::class.java.name)

    /**
     * Nothing may escape from here. The `java.lang.instrument` contract aborts the whole target
     * JVM on an uncaught exception from `premain`, so any bug in the agent's own startup would
     * take the application down with it. A failed start is logged and the agent stays inert.
     */
    @JvmStatic
    fun premain(
        agentArgs: String?,
        instrumentation: Instrumentation,
    ) {
        try {
            start(agentArgs, instrumentation)
        } catch (e: Throwable) {
            log.log(Level.ERROR, "yukon: agent failed to start and is disabled for this JVM", e)
        }
    }

    /**
     * Everything [start] set up in this JVM. [stop] takes it all down again: the shutdown hook,
     * the scheduler, and every class file transformer. Nothing in a `-javaagent` launch calls
     * [stop]; it exists so a test can start the agent for real and leave no trace behind.
     *
     * [endpointTransformer] is null exactly when [AgentConfig.endpointsEnabled] was false, or
     * when endpoint instrumentation failed to install; either way there is nothing to uninstall
     * for it.
     */
    internal class Running(
        val scheduler: ExportScheduler,
        private val instrumentation: Instrumentation,
        private val yukonInstrumentation: YukonInstrumentation,
        private val transformer: ResettableClassFileTransformer,
        private val shutdownHook: Thread,
        private val endpointInstrumentation: EndpointInstrumentation? = null,
        val endpointTransformer: ResettableClassFileTransformer? = null,
    ) {
        fun stop() {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
            scheduler.stop()
            yukonInstrumentation.uninstall(instrumentation, transformer)
            if (endpointInstrumentation != null && endpointTransformer != null) {
                endpointInstrumentation.uninstall(instrumentation, endpointTransformer)
            }
        }
    }

    /**
     * Returns what was started, or null if the agent did not start: either [AgentConfig.enabled]
     * is false, or the bootstrap holder could not be installed. `internal` rather than `private`
     * so a test can drive this directly with a real [Instrumentation] and stop what it started,
     * without going through [premain]'s `void` contract.
     */
    internal fun start(
        agentArgs: String?,
        instrumentation: Instrumentation,
    ): Running? {
        val config = AgentConfig.parse(agentArgs)
        if (!config.enabled) {
            log.log(Level.INFO, "yukon: disabled by configuration, nothing will be instrumented or exported")
            return null
        }

        val registry = ProbeRegistry()
        val endpointRegistry = EndpointRegistry()
        val staticBaselineMismatchDetector = StaticBaselineMismatchDetector()

        val yukonInstrumentation = YukonInstrumentation(config, registry, staticBaselineMismatchDetector)
        val transformer =
            try {
                yukonInstrumentation.install(instrumentation)
            } catch (e: BootstrapInstallException) {
                // Nothing is instrumented and nothing is exported. A missing instance is a visible
                // signal at the collector; an instance reporting zero hits everywhere would not be.
                log.log(Level.ERROR, "yukon: could not install the bootstrap holder; the agent is disabled for this JVM", e)
                return null
            }

        var endpointInstrumentation: EndpointInstrumentation? = null
        var endpointTransformer: ResettableClassFileTransformer? = null
        if (config.endpointsEnabled) {
            try {
                val modules = filterEndpointModules(EndpointModules.discover(), config.otelBridgeEnabled)
                val instance = EndpointInstrumentation(endpointRegistry, modules)
                endpointTransformer = instance.install(instrumentation)
                endpointInstrumentation = instance
            } catch (e: Throwable) {
                // An endpoint-path failure must never take down the method tier that already
                // installed successfully above; it only means this JVM reports no endpoints.
                log.log(Level.ERROR, "yukon: endpoint instrumentation failed to install, continuing without endpoint tracking", e)
            }
        } else {
            log.log(Level.INFO, "yukon: endpointsEnabled=false, no framework's endpoints will be instrumented")
        }

        val exporter = HttpOtlpStyleExporter(config.collectorEndpoint, config.authToken)
        val scheduler = ExportScheduler(config, registry, endpointRegistry, exporter)
        scheduler.start()

        if (config.staticBaselineEnabled) {
            startStaticBaselineScan(config, exporter, registry, staticBaselineMismatchDetector)
        }

        val shutdownHook = Thread({ scheduler.flushOnShutdown(SHUTDOWN_FLUSH_TIMEOUT) }, "yukon-shutdown-hook")
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        return Running(
            scheduler,
            instrumentation,
            yukonInstrumentation,
            transformer,
            shutdownHook,
            endpointInstrumentation,
            endpointTransformer,
        )
    }

    /**
     * Drops the route bridge module, named `"otel"`, from [modules] unless [otelBridgeEnabled]
     * opts into it. Every other discovered module passes through untouched.
     *
     * `internal` rather than `private` so a test can pin this rule directly against fake
     * [EndpointModule] instances, without installing a real agent and inspecting which framework
     * classes it ended up matching.
     */
    internal fun filterEndpointModules(
        modules: List<EndpointModule>,
        otelBridgeEnabled: Boolean,
    ): List<EndpointModule> = if (otelBridgeEnabled) modules else modules.filterNot { it.name == OTEL_BRIDGE_MODULE_NAME }

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
        val scanner = StaticBaselineScanner(config.instrumentedPackagePrefixes, config.excludedPackagePrefixes)
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
