package io.github.lukedevops.yukon

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.dependencies.ListedDependency
import io.github.lukedevops.yukon.dependencies.LoadedDependencyCounter
import io.github.lukedevops.yukon.dependencies.StartupClasspathLister
import io.github.lukedevops.yukon.export.DependencyDiscoverySource
import io.github.lukedevops.yukon.export.ExportScheduler
import io.github.lukedevops.yukon.export.Exporter
import io.github.lukedevops.yukon.export.HttpOtlpStyleExporter
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.instrumentation.BootstrapInstallException
import io.github.lukedevops.yukon.instrumentation.LoadedClassSweep
import io.github.lukedevops.yukon.instrumentation.YukonInstrumentation
import io.github.lukedevops.yukon.instrumentation.branch.BranchDropCounts
import io.github.lukedevops.yukon.instrumentation.endpoints.EndpointInstrumentation
import io.github.lukedevops.yukon.instrumentation.endpoints.EndpointModules
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import io.github.lukedevops.yukon.instrumentation.staticscan.StaticBaselineMismatchDetector
import io.github.lukedevops.yukon.instrumentation.staticscan.StaticBaselinePublisher
import io.github.lukedevops.yukon.instrumentation.staticscan.StaticBaselineScanner
import io.github.lukedevops.yukon.registry.DependencyOrigin
import io.github.lukedevops.yukon.registry.DependencyRegistry
import io.github.lukedevops.yukon.registry.EndpointRegistry
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.lang.System.Logger.Level
import java.lang.instrument.Instrumentation
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
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
        val dependencyRegistry: DependencyRegistry = DependencyRegistry(),
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

        val registry = ProbeRegistry(confirmsDefinitions = true)
        val endpointRegistry = EndpointRegistry()
        val dependencyRegistry = DependencyRegistry()
        val staticBaselineMismatchDetector = StaticBaselineMismatchDetector()
        val branchDropCounts = BranchDropCounts()

        val yukonInstrumentation =
            YukonInstrumentation(config, registry, staticBaselineMismatchDetector, branchDropCounts = branchDropCounts)
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
        val scheduler =
            ExportScheduler(
                config,
                registry,
                endpointRegistry,
                exporter,
                branchDropCounts = branchDropCounts,
                loadedClassSweep =
                    LoadedClassSweep(
                        instrumentation,
                        registry,
                        config,
                        LoadedDependencyCounter(dependencyRegistry, config.instrumentedPackagePrefixes, config.excludedPackagePrefixes),
                    ),
                dependencyRegistry = dependencyRegistry,
            )
        scheduler.start()

        startDependencyListing(config, dependencyRegistry)

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
            dependencyRegistry,
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

    /**
     * Lists the startup classpath's dependencies on its own daemon thread, off `premain`: judging
     * whether a jar is the adopter's own reads every entry name, and in a fat jar that means
     * streaming each nested jar. Runs once per process, always. See ADR 0030.
     */
    private fun startDependencyListing(
        config: AgentConfig,
        registry: DependencyRegistry,
    ) {
        val lister =
            StartupClasspathLister(
                config.instrumentedPackagePrefixes,
                config.excludedPackagePrefixes,
                onNotADependency = registry::recordNotADependency,
            )
        val worker =
            Thread({
                recordAgentJar(
                    Agent::class.java.protectionDomain.codeSource
                        ?.location,
                    registry,
                )
                runDependencyListing(lister::list, registry)
            }, "yukon-dependency-listing")
        worker.isDaemon = true
        worker.start()
    }

    /**
     * Records the jar this agent was loaded from, [location], as not a dependency. A `-jar` launch
     * leaves the agent jar off `java.class.path`, so the listing never judges it, and without this
     * the sweep would read the whole agent jar once only to find `Premain-Class` in it. Nothing is
     * recorded when [location] is not a jar file, as in a test run from a classes directory.
     */
    internal fun recordAgentJar(
        location: URL?,
        registry: DependencyRegistry,
    ) {
        try {
            if (location == null || location.protocol != "file") return
            val path = Paths.get(location.toURI())
            if (Files.isRegularFile(path)) registry.recordNotADependency(DependencyOrigin.FlatJar(path.toAbsolutePath()))
        } catch (e: Exception) {
            log.log(Level.DEBUG, "yukon: could not read the agent's own location $location", e)
        }
    }

    /**
     * Runs [list] to completion, then registers everything it found and marks the listing
     * complete. Nothing escapes: a failure is logged at WARNING and registers nothing, so the
     * collector sees no dependencies from this instance rather than a partial list it would read
     * as the whole classpath.
     *
     * `internal` so a test can drive a failing listing without a real classpath.
     */
    internal fun runDependencyListing(
        list: () -> List<ListedDependency>,
        registry: DependencyRegistry,
    ) {
        try {
            val listed = list()
            for (dependency in listed) {
                registry.register(
                    dependency.identities,
                    dependency.identitySource,
                    dependency.location,
                    DependencyDiscoverySource.STARTUP_CLASSPATH,
                    dependency.classCount,
                    dependency.origin,
                )
            }
            registry.markListingComplete()
        } catch (t: Throwable) {
            log.log(Level.WARNING, "yukon: the startup dependency listing failed; no dependencies will be reported", t)
        }
    }
}
