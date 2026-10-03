package dev.otherlode

import dev.otherlode.config.AgentConfig
import dev.otherlode.config.IncludeRulesRefusal
import dev.otherlode.config.MainClassSuggestion
import dev.otherlode.dependencies.DependencyResolver
import dev.otherlode.dependencies.JarClassifier
import dev.otherlode.dependencies.ListedDependency
import dev.otherlode.dependencies.LoadedDependencyCounter
import dev.otherlode.dependencies.StartupClasspathLister
import dev.otherlode.export.DependencyDiscoverySource
import dev.otherlode.export.ExportScheduler
import dev.otherlode.export.Exporter
import dev.otherlode.export.HttpOtlpStyleExporter
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.forNewRun
import dev.otherlode.instrumentation.BootstrapInstallException
import dev.otherlode.instrumentation.LoadedClassSweep
import dev.otherlode.instrumentation.OtherlodeInstrumentation
import dev.otherlode.instrumentation.branch.BranchDropCounts
import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.instrumentation.endpoints.EndpointModules
import dev.otherlode.instrumentation.endpoints.HandlerForwarders
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import dev.otherlode.instrumentation.staticscan.BaselineReferenceFilter
import dev.otherlode.instrumentation.staticscan.StaticBaselineMismatchDetector
import dev.otherlode.instrumentation.staticscan.StaticBaselinePublisher
import dev.otherlode.instrumentation.staticscan.StaticBaselineScanner
import dev.otherlode.instrumentation.staticscan.StaticBaselineSender
import dev.otherlode.registry.DependencyOrigin
import dev.otherlode.registry.DependencyRegistry
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ExternalClassRegistry
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.lang.System.Logger.Level
import java.lang.instrument.Instrumentation
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration

/** `-javaagent:otherlode-agent.jar` entry point. */
object Agent {
    private val SHUTDOWN_FLUSH_TIMEOUT: Duration = Duration.ofSeconds(10)
    private val TEST_RUN_SCAN_WAIT: Duration = Duration.ofSeconds(15)
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
            log.log(Level.ERROR, "otherlode: agent failed to start and is disabled for this JVM", e)
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
     *
     * [resource] is the one every payload from this start carries, run id included.
     */
    internal class Running(
        val scheduler: ExportScheduler,
        private val instrumentation: Instrumentation,
        private val otherlodeInstrumentation: OtherlodeInstrumentation,
        private val transformer: ResettableClassFileTransformer,
        private val shutdownHook: Thread,
        private val endpointInstrumentation: EndpointInstrumentation? = null,
        val endpointTransformer: ResettableClassFileTransformer? = null,
        val dependencyRegistry: DependencyRegistry = DependencyRegistry(),
        val resource: ResourceAttributes,
    ) {
        fun stop() {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
            scheduler.stop()
            otherlodeInstrumentation.uninstall(instrumentation, transformer)
            if (endpointInstrumentation != null && endpointTransformer != null) {
                endpointInstrumentation.uninstall(instrumentation, endpointTransformer)
            }
        }
    }

    /**
     * The `includePackages` value to suggest in the refusal, from this JVM's `sun.java.command`.
     * Null when there is nothing to suggest or the property cannot be read.
     */
    private fun suggestionForThisJvm(): MainClassSuggestion? =
        try {
            MainClassSuggestion.fromCommand(System.getProperty("sun.java.command"))
        } catch (e: Exception) {
            null
        }

    /**
     * Returns what was started, or null if the agent did not start: [AgentConfig.enabled] is
     * false, [AgentConfig.instrumentedPackagePrefixes] is empty, or the bootstrap holder could not
     * be installed. Both configuration checks run before anything is constructed, so a refused
     * start leaves no thread, transformer or registry behind. `internal` rather than `private` so a
     * test can drive this directly with a real [Instrumentation] and stop what it started,
     * without going through [premain]'s `void` contract.
     */
    internal fun start(
        agentArgs: String?,
        instrumentation: Instrumentation,
        addShutdownHook: (Thread) -> Unit = { Runtime.getRuntime().addShutdownHook(it) },
    ): Running? {
        val config = AgentConfig.parse(agentArgs)
        if (!config.enabled) {
            log.log(Level.INFO, "otherlode: disabled by configuration, nothing will be instrumented or exported")
            return null
        }
        if (config.instrumentedPackagePrefixes.isEmpty()) {
            log.log(Level.ERROR, IncludeRulesRefusal.message(suggestionForThisJvm()))
            return null
        }

        val registry = ProbeRegistry(confirmsDefinitions = true)
        val endpointRegistry = EndpointRegistry()
        val dependencyRegistry = DependencyRegistry(indexClassNames = config.staticBaselineEnabled)
        // One resolver for both users, so its caches and its registrations of jars discovered by
        // load are shared: the loaded-class count and the external-class mapping must agree on a
        // jar's dependency id.
        val dependencyResolver =
            DependencyResolver(dependencyRegistry, JarClassifier(config.instrumentedPackagePrefixes, config.excludedPackagePrefixes))
        val externalClassRegistry =
            ExternalClassRegistry(
                dependencyRegistry::isListingComplete,
                dependencyResolver::resolveLocation,
                dependencyRegistry::isSendable,
            )
        val staticBaselineMismatchDetector = StaticBaselineMismatchDetector()
        val branchDropCounts = BranchDropCounts()

        // Found before the method tier installs: its analysis writes the forwarder table, and only
        // for the handler interfaces these modules name (ADR 0035).
        val endpointModules = if (config.endpointsEnabled) discoverEndpointModules(config) else null
        val handlerForwarders = HandlerForwarders(endpointModules.orEmpty().flatMapTo(sortedSetOf()) { it.handlerInterfaces })

        val otherlodeInstrumentation =
            OtherlodeInstrumentation(
                config,
                registry,
                staticBaselineMismatchDetector,
                branchDropCounts = branchDropCounts,
                externalClassRegistry = externalClassRegistry,
                handlerForwarders = handlerForwarders,
            )
        val transformer =
            try {
                otherlodeInstrumentation.install(instrumentation)
            } catch (e: BootstrapInstallException) {
                // Nothing is instrumented and nothing is exported. A missing instance is a visible
                // signal at the collector; an instance reporting zero hits everywhere would not be.
                log.log(Level.ERROR, "otherlode: could not install the bootstrap holder; the agent is disabled for this JVM", e)
                return null
            }

        var endpointInstrumentation: EndpointInstrumentation? = null
        var endpointTransformer: ResettableClassFileTransformer? = null
        if (endpointModules != null) {
            try {
                val instance = EndpointInstrumentation(endpointRegistry, endpointModules, handlerForwarders = handlerForwarders)
                endpointTransformer = instance.install(instrumentation)
                endpointInstrumentation = instance
            } catch (e: Throwable) {
                // An endpoint-path failure must never take down the method tier that already
                // installed successfully above; it only means this JVM reports no endpoints.
                log.log(Level.ERROR, "otherlode: endpoint instrumentation failed to install, continuing without endpoint tracking", e)
            }
        } else if (!config.endpointsEnabled) {
            log.log(Level.INFO, "otherlode: endpointsEnabled=false, no framework's endpoints will be instrumented")
        }

        // Both transformers are installed. A failure from here on would leave classes woven while
        // nothing is ever exported, so it takes down everything set up so far before premain logs
        // it. The threads start last, once nothing after them can fail.
        var scheduler: ExportScheduler? = null
        var shutdownHook: Thread? = null
        try {
            // Made once here and shared, so every payload this process sends names the same run.
            val resource = ResourceAttributes.forNewRun(config)
            val exporter = HttpOtlpStyleExporter(config.collectorEndpoint, config.authToken)
            // Shared by the scan, which sends first with the exporter's full retries, and the
            // scheduler, which resends what failed with one attempt per chunk.
            val staticBaselineSender =
                if (config.staticBaselineEnabled) {
                    StaticBaselineSender(
                        exporter,
                        retryExporter = HttpOtlpStyleExporter(config.collectorEndpoint, config.authToken, maxAttempts = 1),
                    )
                } else {
                    null
                }
            val started =
                ExportScheduler(
                    config,
                    resource,
                    registry,
                    endpointRegistry,
                    exporter,
                    branchDropCounts = branchDropCounts,
                    loadedClassSweep =
                        LoadedClassSweep(
                            instrumentation,
                            registry,
                            config,
                            LoadedDependencyCounter(dependencyRegistry, dependencyResolver::resolve),
                        ),
                    dependencyRegistry = dependencyRegistry,
                    externalClassRegistry = externalClassRegistry,
                    staticBaselineSender = staticBaselineSender,
                )
            scheduler = started
            started.start()
            val scanWorker =
                if (staticBaselineSender != null) {
                    val referenceFilter = BaselineReferenceFilter(dependencyRegistry, externalClassRegistry)
                    staticBaselineScanThread(
                        config,
                        resource,
                        exporter,
                        staticBaselineSender,
                        registry,
                        staticBaselineMismatchDetector,
                        referenceFilter,
                    )
                } else {
                    null
                }
            val hook =
                Thread({
                    shutdown(config.testRun, scanWorker, TEST_RUN_SCAN_WAIT) { started.flushOnShutdown(SHUTDOWN_FLUSH_TIMEOUT) }
                }, "otherlode-shutdown-hook")
            addShutdownHook(hook)
            shutdownHook = hook
            startDependencyListing(config, dependencyRegistry)
            scanWorker?.start()
            return Running(
                started,
                instrumentation,
                otherlodeInstrumentation,
                transformer,
                hook,
                endpointInstrumentation,
                endpointTransformer,
                dependencyRegistry,
                resource,
            )
        } catch (e: Throwable) {
            // Each step is tried on its own, so one that fails neither stops the rest nor hides
            // the failure that started the rollback.
            fun undo(step: () -> Unit) = runCatching(step).exceptionOrNull()?.let(e::addSuppressed)
            shutdownHook?.let { undo { Runtime.getRuntime().removeShutdownHook(it) } }
            scheduler?.let { undo(it::stop) }
            undo { otherlodeInstrumentation.uninstall(instrumentation, transformer) }
            if (endpointInstrumentation != null && endpointTransformer != null) {
                undo { endpointInstrumentation.uninstall(instrumentation, endpointTransformer) }
            }
            throw e
        }
    }

    /**
     * The endpoint modules this JVM runs, or null when discovery failed. A failure here is logged
     * the same way a failed endpoint install is, and the method tier still installs.
     */
    private fun discoverEndpointModules(config: AgentConfig): List<EndpointModule>? =
        try {
            filterEndpointModules(EndpointModules.discover(), config.otelBridgeEnabled)
        } catch (e: Throwable) {
            log.log(Level.ERROR, "otherlode: endpoint instrumentation failed to install, continuing without endpoint tracking", e)
            null
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
     * The thread the scan runs on, unstarted: off `premain`, so a full classpath walk never adds
     * latency to the target app's startup. Fires once per process: no periodic re-scan, matching
     * "static" in the name. See [StaticBaselinePublisher] for what happens once the scan is done.
     */
    private fun staticBaselineScanThread(
        config: AgentConfig,
        resource: ResourceAttributes,
        exporter: Exporter,
        sender: StaticBaselineSender,
        registry: ProbeRegistry,
        mismatchDetector: StaticBaselineMismatchDetector,
        referenceFilter: BaselineReferenceFilter,
    ): Thread {
        val scanner = StaticBaselineScanner(config.instrumentedPackagePrefixes, config.excludedPackagePrefixes)
        val publisher =
            StaticBaselinePublisher(
                scanner::scan,
                exporter,
                registry,
                mismatchDetector,
                filterReferences = referenceFilter::filter,
                sender = sender,
            )
        val worker = Thread({ publisher.run(resource) }, "otherlode-static-baseline-scan")
        worker.isDaemon = true
        return worker
    }

    /**
     * What the shutdown hook does: [flush], then, for a test run, wait up to [scanWait] for the
     * static baseline scan on [scanWorker] to end. A test JVM often exits before the scan ends, and
     * the scan is where a collector reads the edges of test classes that never loaded. The flush
     * goes first, so a JVM halted during the wait still sends the final manifest. The scan sends
     * through the exporter, not the scheduler's pool, so it still works after the flush.
     */
    internal fun shutdown(
        testRun: Boolean,
        scanWorker: Thread?,
        scanWait: Duration,
        flush: () -> Unit,
    ) {
        flush()
        if (!testRun || scanWorker == null) return
        scanWorker.join(scanWait.toMillis())
        if (scanWorker.isAlive) {
            log.log(
                Level.WARNING,
                "otherlode: the static baseline scan did not end within ${scanWait.seconds}s of shutdown, so this test run may send no complete scan",
            )
        }
    }

    /**
     * Lists the startup classpath's dependencies on its own daemon thread, off `premain`: judging
     * whether a jar is the adopter's own reads every entry name, and in a fat jar that means
     * streaming each nested jar. Runs once per process, always. With the static baseline enabled
     * it also keeps every dependency's class names for the registry's class index.
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
                keepClassNames = config.staticBaselineEnabled,
            )
        val worker =
            Thread({
                recordAgentJar(
                    Agent::class.java.protectionDomain.codeSource
                        ?.location,
                    registry,
                )
                runDependencyListing(lister::list, registry)
            }, "otherlode-dependency-listing")
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
            log.log(Level.DEBUG, "otherlode: could not read the agent's own location $location", e)
        }
    }

    /**
     * Runs [list] to completion, then registers everything it found, with its class names for the
     * class index, and marks the listing complete. Nothing escapes: a failure is logged at WARNING,
     * registers nothing and marks the listing failed, so the collector sees no dependencies from
     * this instance rather than a partial list it would read as the whole classpath, and nothing
     * waiting on the listing waits any longer.
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
                    dependency.classNames,
                )
            }
            registry.markListingComplete()
        } catch (t: Throwable) {
            log.log(Level.WARNING, "otherlode: the startup dependency listing failed; no dependencies will be reported", t)
            registry.markListingFailed()
        }
    }
}
