package io.github.lukedevops.yukon

import com.sun.net.httpserver.HttpServer
import io.github.lukedevops.yukon.dependencies.ListedDependency
import io.github.lukedevops.yukon.export.DependencyDiscoverySource
import io.github.lukedevops.yukon.export.DependencyIdentity
import io.github.lukedevops.yukon.export.DependencyIdentitySource
import io.github.lukedevops.yukon.export.ProtoPayloadCodec
import io.github.lukedevops.yukon.export.StaticBaseline
import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import io.github.lukedevops.yukon.registry.DependencyOrigin
import io.github.lukedevops.yukon.registry.DependencyRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers
import org.junit.jupiter.api.io.TempDir
import java.lang.instrument.Instrumentation
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTest {
    /**
     * Compares the thread names present before and after a call, rather than asserting an
     * absolute count. The test JVM can already be carrying `yukon-*` threads left by other test
     * classes in the same Gradle test worker, so an absolute "no such thread exists" assertion
     * would be flaky for reasons unrelated to this test.
     */
    private fun currentThreadNames(): Set<String> = Thread.getAllStackTraces().keys.mapTo(mutableSetOf()) { it.name }

    @Test
    fun `enabled=false starts no yukon threads at all`() {
        val instrumentation = ByteBuddyAgent.install()
        val before = currentThreadNames()

        val running = Agent.start("enabled=false", instrumentation)

        assertNull(running)
        val newThreadNames = currentThreadNames() - before
        assertTrue(newThreadNames.none { it.startsWith("yukon-") }, "unexpected new yukon- threads: $newThreadNames")
    }

    /**
     * An [Instrumentation] that records the name of every method called on it and otherwise does
     * nothing. Installing a transformer, a bootstrap search path entry or a module read edge all go
     * through it, so an empty record after a call means the call installed nothing.
     */
    private fun recordingInstrumentation(calls: MutableList<String>): Instrumentation =
        Proxy.newProxyInstance(
            Instrumentation::class.java.classLoader,
            arrayOf(Instrumentation::class.java),
        ) { _, method, _ ->
            calls += method.name
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                else -> null
            }
        } as Instrumentation

    @Test
    fun `no include rules refuses to start, installs nothing and starts no yukon threads`() {
        for (args in listOf(null, "", "includePackages=", "includePackages=;", "includePackages= ; ", "excludePackages=com.acme")) {
            val calls = mutableListOf<String>()
            val before = currentThreadNames()

            val running = Agent.start(args, recordingInstrumentation(calls))

            assertNull(running, "args '$args' must be refused")
            assertEquals(emptyList(), calls, "args '$args' must not touch Instrumentation")
            val newThreadNames = currentThreadNames() - before
            assertTrue(newThreadNames.none { it.startsWith("yukon-") }, "args '$args' started yukon- threads: $newThreadNames")
        }
    }

    @Test
    fun `premain with no include rules returns quietly`() {
        val calls = mutableListOf<String>()

        Agent.premain(null, recordingInstrumentation(calls))

        assertEquals(emptyList(), calls)
    }

    @Test
    fun `enabled=false with no include rules still returns null and installs nothing`() {
        val calls = mutableListOf<String>()
        val before = currentThreadNames()

        val running = Agent.start("enabled=false", recordingInstrumentation(calls))

        assertNull(running)
        assertEquals(emptyList(), calls)
        assertTrue((currentThreadNames() - before).none { it.startsWith("yukon-") })
    }

    @Test
    fun `enabled (the default) starts the export scheduler`() {
        val instrumentation = ByteBuddyAgent.install()
        val before = currentThreadNames()

        // Scoped to a package nothing in this JVM ever loads, so the transformer never matches a
        // real class while it is installed. The interval keeps the scheduler from attempting a
        // flush before stop() removes it along with the shutdown hook, so the test JVM never
        // sends anything to a collector, at exit included.
        val running =
            Agent.start(
                "includePackages=io.github.lukedevops.yukon.neverloaded.fixture,flushIntervalSeconds=3600",
                instrumentation,
            )

        try {
            assertNotNull(running, "enabled must start the agent")
            val newThreadNames = currentThreadNames() - before
            assertTrue(newThreadNames.any { it.startsWith("yukon-") }, "expected a new yukon- thread, found: $newThreadNames")
        } finally {
            running?.stop()
        }
    }

    @Test
    fun `endpointsEnabled=false leaves Running's endpoint transformer null, and stop() still works`() {
        val instrumentation = ByteBuddyAgent.install()

        val running =
            Agent.start(
                "includePackages=io.github.lukedevops.yukon.neverloaded.fixture,flushIntervalSeconds=3600,endpointsEnabled=false",
                instrumentation,
            )

        try {
            assertNotNull(running)
            assertNull(running.endpointTransformer)
        } finally {
            running?.stop()
        }
    }

    @Test
    fun `endpointsEnabled defaults to true, leaving Running's endpoint transformer non-null, and stop() still works`() {
        val instrumentation = ByteBuddyAgent.install()

        val running =
            Agent.start(
                "includePackages=io.github.lukedevops.yukon.neverloaded.fixture,flushIntervalSeconds=3600",
                instrumentation,
            )

        try {
            assertNotNull(running)
            assertNotNull(running.endpointTransformer)
        } finally {
            running?.stop()
        }
    }

    @Test
    fun `premain never propagates a failure from start, since that would abort the target JVM`() {
        // Every Instrumentation call throws, so start() fails at its first use of it, before any
        // thread or transformer exists to clean up.
        val brokenInstrumentation =
            Proxy.newProxyInstance(
                Instrumentation::class.java.classLoader,
                arrayOf(Instrumentation::class.java),
            ) { _, method, _ -> throw IllegalStateException("simulated instrumentation failure in ${method.name}") } as Instrumentation

        Agent.premain("includePackages=io.github.lukedevops.yukon.neverloaded.fixture", brokenInstrumentation)
    }

    @Test
    fun `staticBaselineEnabled starts the scan off premain and delivers the baseline to the collector`() {
        val received = CompletableFuture<StaticBaseline>()
        val collector = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        collector.createContext("/v1/yukon/static-baseline") { exchange ->
            received.complete(ProtoPayloadCodec.decodeStaticBaseline(exchange.requestBody.readBytes()))
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        collector.start()
        val instrumentation = ByteBuddyAgent.install()
        val before = currentThreadNames()

        // The test classpath is this JVM's java.class.path, and it carries the compiled fixtures
        // under com.example.target, so a scan of the default roots must declare one of them.
        val running =
            Agent.start(
                "includePackages=com.example.target,flushIntervalSeconds=3600,endpointsEnabled=false," +
                    "staticBaselineEnabled=true,endpoint=http://localhost:${collector.address.port}",
                instrumentation,
            )
        try {
            assertNotNull(running)
            assertTrue((currentThreadNames() - before).any { it == "yukon-static-baseline-scan" } || received.isDone)
            val baseline = received.get(30, TimeUnit.SECONDS)
            assertTrue(baseline.declaredClasses.any { it.className == "com.example.target.SampleTarget" })
            assertEquals(1, baseline.chunkCount)
        } finally {
            running?.stop()
            collector.stop(0)
        }
    }

    @Test
    fun `every payload kind from one agent start carries the same non-empty run id`() {
        val runIds = ConcurrentHashMap<String, MutableSet<String>>()
        val baselineReceived = CompletableFuture<Unit>()
        val collector = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        for (path in listOf("deltas", "manifest", "static-baseline")) {
            collector.createContext("/v1/yukon/$path") { exchange ->
                val bytes = exchange.requestBody.readBytes()
                val runId =
                    when (path) {
                        "deltas" -> ProtoPayloadCodec.decodeDeltaBatch(bytes).resource.runId
                        "manifest" -> ProtoPayloadCodec.decodeProbeManifest(bytes).resource.runId
                        else -> ProtoPayloadCodec.decodeStaticBaseline(bytes).resource.runId
                    }
                runIds.computeIfAbsent(path) { ConcurrentHashMap.newKeySet() } += runId
                if (path == "static-baseline") baselineReceived.complete(Unit)
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
        }
        collector.start()
        val instrumentation = ByteBuddyAgent.install()

        val running =
            Agent.start(
                "includePackages=com.example.target,flushIntervalSeconds=3600,endpointsEnabled=false," +
                    "staticBaselineEnabled=true,endpoint=http://localhost:${collector.address.port}",
                instrumentation,
            )
        try {
            assertNotNull(running)
            baselineReceived.get(30, TimeUnit.SECONDS)
            running.scheduler.flush()

            val expected = running.resource.runId
            assertTrue(expected.isNotEmpty())
            assertEquals(setOf("deltas", "manifest", "static-baseline"), runIds.keys)
            assertTrue(runIds.values.all { it == setOf(expected) }, "every payload must carry run id $expected: $runIds")
        } finally {
            running?.stop()
            collector.stop(0)
        }
    }

    @Test
    fun `two agent starts get different run ids`() {
        val instrumentation = ByteBuddyAgent.install()
        val args = "includePackages=io.github.lukedevops.yukon.neverloaded.fixture,flushIntervalSeconds=3600,serviceInstanceId=pinned"

        val first = assertNotNull(Agent.start(args, instrumentation))
        first.stop()
        val second = assertNotNull(Agent.start(args, instrumentation))
        second.stop()

        assertEquals("pinned", first.resource.serviceInstanceId)
        assertEquals(first.resource.serviceInstanceId, second.resource.serviceInstanceId)
        assertNotEquals(first.resource.runId, second.resource.runId, "a pinned instance id must still get a new run id per start")
    }

    @Test
    fun `start runs the dependency listing on its own thread and marks it complete`() {
        val instrumentation = ByteBuddyAgent.install()

        val running =
            Agent.start(
                "includePackages=io.github.lukedevops.yukon.neverloaded.fixture,flushIntervalSeconds=3600,endpointsEnabled=false",
                instrumentation,
            )
        try {
            assertNotNull(running)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!running.dependencyRegistry.isListingComplete && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(running.dependencyRegistry.isListingComplete, "the listing thread never finished")
            // The test classpath carries the Kotlin stdlib as a jar, and nothing in it is under the include rules.
            assertTrue(running.dependencyRegistry.entries().any { entry -> entry.identities.any { it.artifactId == "kotlin-stdlib" } })
            assertEquals(0, running.dependencyRegistry.classIndexSize, "no class index without the static baseline")
        } finally {
            running?.stop()
        }
    }

    @Test
    fun `with the static baseline enabled the baseline keeps references into listed dependencies and drops JDK names`() {
        val received = CompletableFuture<StaticBaseline>()
        val collector = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        collector.createContext("/v1/yukon/static-baseline") { exchange ->
            received.complete(ProtoPayloadCodec.decodeStaticBaseline(exchange.requestBody.readBytes()))
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        collector.start()
        val instrumentation = ByteBuddyAgent.install()

        // The Kotlin fixtures under com.example.target reference kotlin-stdlib, a jar on the test
        // classpath the listing registers as a dependency, and java.lang, which the platform provides.
        val running =
            Agent.start(
                "includePackages=com.example.target,flushIntervalSeconds=3600,endpointsEnabled=false," +
                    "staticBaselineEnabled=true,endpoint=http://localhost:${collector.address.port}",
                instrumentation,
            )
        try {
            assertNotNull(running)
            val references =
                received.get(30, TimeUnit.SECONDS).declaredClasses.flatMap { declared ->
                    declared.referencedClasses + declared.methods.flatMap { it.referencedClasses }
                }
            assertTrue(references.any { it.startsWith("kotlin.") }, "no reference into kotlin-stdlib was kept: $references")
            assertTrue(references.none { it.startsWith("java.") }, "a JDK name was sent: $references")
            assertEquals(0, running.dependencyRegistry.classIndexSize, "the class index is released once the baseline is filtered")
        } finally {
            running?.stop()
            collector.stop(0)
        }
    }

    @Test
    fun `a listing that throws registers nothing, leaves the listing incomplete, and does not propagate`() {
        val registry = DependencyRegistry()

        Agent.runDependencyListing({ throw IllegalStateException("simulated listing failure") }, registry)

        assertFalse(registry.isListingComplete)
        assertTrue(registry.entries().isEmpty())
        assertEquals(DependencyRegistry.ListingOutcome.FAILED, registry.awaitListing(Duration.ofMillis(1)))
    }

    @Test
    fun `the agent's own jar is recorded as not a dependency, and a classes directory records nothing`(
        @TempDir dir: Path,
    ) {
        val jar = Files.write(dir.resolve("agent.jar"), ByteArray(0))
        val registry = DependencyRegistry()

        Agent.recordAgentJar(jar.toUri().toURL(), registry)
        Agent.recordAgentJar(dir.toUri().toURL(), registry)
        Agent.recordAgentJar(null, registry)

        assertTrue(registry.isJudgedNotADependency(DependencyOrigin.FlatJar(jar)))
        assertFalse(registry.isJudgedNotADependency(DependencyOrigin.FlatJar(dir)))
    }

    @Test
    fun `a listing that succeeds registers each dependency as found on the startup classpath`() {
        val registry = DependencyRegistry()
        val listed =
            ListedDependency(
                listOf(DependencyIdentity("g", "a", "1")),
                DependencyIdentitySource.POM_PROPERTIES,
                "/libs/a.jar",
                classCount = 2,
                origin = DependencyOrigin.FlatJar(Path.of("/libs/a.jar")),
                classNames = setOf("org.a.A"),
            )

        Agent.runDependencyListing({ listOf(listed) }, registry)

        assertTrue(registry.isListingComplete)
        val entry = registry.entries().single()
        assertEquals(DependencyDiscoverySource.STARTUP_CLASSPATH, entry.discoverySource)
        assertEquals(DependencyOrigin.FlatJar(Path.of("/libs/a.jar")), entry.origin)
        assertEquals(2, entry.classCount)
        assertEquals(null, registry.dependencyForClass("org.a.A"), "an unindexed registry keeps no class names")
    }

    @Test
    fun `a listing into an indexing registry indexes each dependency's class names`() {
        val registry = DependencyRegistry(indexClassNames = true)
        val listed =
            ListedDependency(
                listOf(DependencyIdentity("g", "a", "1")),
                DependencyIdentitySource.POM_PROPERTIES,
                "/libs/a.jar",
                classCount = 1,
                origin = DependencyOrigin.FlatJar(Path.of("/libs/a.jar")),
                classNames = setOf("org.a.A"),
            )

        Agent.runDependencyListing({ listOf(listed) }, registry)

        assertEquals(registry.entries().single().dependencyId, registry.dependencyForClass("org.a.A"))
    }

    @Test
    fun `filterEndpointModules drops the otel module unless otelBridgeEnabled, and keeps every other module either way`() {
        val otelModule = fakeEndpointModule("otel")
        val jdkModule = fakeEndpointModule("jdk-httpserver")
        val modules = listOf(jdkModule, otelModule)

        assertEquals(listOf(jdkModule), Agent.filterEndpointModules(modules, otelBridgeEnabled = false))
        assertEquals(listOf(jdkModule, otelModule), Agent.filterEndpointModules(modules, otelBridgeEnabled = true))
    }

    @Test
    fun `filterEndpointModules is a no-op when no module is named otel`() {
        val modules = listOf(fakeEndpointModule("jdk-httpserver"), fakeEndpointModule("spring-mvc"))

        assertEquals(modules, Agent.filterEndpointModules(modules, otelBridgeEnabled = false))
    }
}

/** A minimal [EndpointModule] whose only meaningful behaviour is its [EndpointModule.name]. */
private fun fakeEndpointModule(moduleName: String): EndpointModule =
    object : EndpointModule {
        override val name: String = moduleName

        override fun typeMatcher(): ElementMatcher<in TypeDescription> = ElementMatchers.any()

        override fun transform(
            builder: DynamicType.Builder<*>,
            typeDescription: TypeDescription,
            advice: AdviceBinder,
            classLoader: ClassLoader?,
        ): DynamicType.Builder<*> = builder
    }
