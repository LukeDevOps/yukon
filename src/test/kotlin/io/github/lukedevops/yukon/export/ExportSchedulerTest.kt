package io.github.lukedevops.yukon.export

import io.github.lukedevops.yukon.Agent
import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.dependencies.LoadedDependencyCounter
import io.github.lukedevops.yukon.dependencies.StartupClasspathLister
import io.github.lukedevops.yukon.dependencies.TestJars
import io.github.lukedevops.yukon.instrumentation.LoadedClassSweep
import io.github.lukedevops.yukon.instrumentation.branch.BranchDropCounts
import io.github.lukedevops.yukon.instrumentation.branch.BranchDropReason
import io.github.lukedevops.yukon.registry.DependencyRegistry
import io.github.lukedevops.yukon.registry.EndpointRegistry
import io.github.lukedevops.yukon.registry.ExternalClassRegistry
import io.github.lukedevops.yukon.registry.ProbeMeta
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import org.junit.jupiter.api.io.TempDir
import java.lang.instrument.Instrumentation
import java.lang.ref.Reference
import java.net.URLClassLoader
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.util.logging.Level as JulLevel
import java.util.logging.Logger as JulLogger

private class RecordingExporter : Exporter {
    var deltaBatches = mutableListOf<DeltaBatch>()
    var manifests = mutableListOf<ProbeManifest>()
    var staticBaselines = mutableListOf<StaticBaseline>()

    /** Every confirmed delta batch and manifest, in the order the sends were confirmed. */
    val sent: MutableList<Any> = Collections.synchronizedList(mutableListOf())

    /** When set, every delta send fails and nothing is recorded for it. */
    @Volatile
    var failDeltaBatches = false

    /** When set, the delta send with this number fails, counting every delta send attempt from 1. */
    @Volatile
    var failDeltaSendNumber: Int? = null
    private val deltaSendAttempts = AtomicInteger(0)

    /** Runs at the start of every delta send. It may block, or throw to fail the send. */
    @Volatile
    var beforeDeltaSend: (DeltaBatch) -> Unit = {}

    /** Runs at the start of every manifest send. It may throw to fail the send. */
    @Volatile
    var beforeManifestSend: (ProbeManifest) -> Unit = {}

    /** Runs after every confirmed manifest send. */
    @Volatile
    var afterManifestSend: (ProbeManifest) -> Unit = {}

    override fun exportDeltaBatch(batch: DeltaBatch) {
        val attempt = deltaSendAttempts.incrementAndGet()
        beforeDeltaSend(batch)
        if (failDeltaBatches || attempt == failDeltaSendNumber) throw RuntimeException("collector unreachable")
        deltaBatches += batch
        sent += batch
    }

    override fun exportManifest(manifest: ProbeManifest) {
        beforeManifestSend(manifest)
        manifests += manifest
        sent += manifest
        afterManifestSend(manifest)
    }

    override fun exportStaticBaseline(baseline: StaticBaseline) {
        staticBaselines += baseline
    }
}

private class FailingExporter : Exporter {
    override fun exportDeltaBatch(batch: DeltaBatch) = throw RuntimeException("collector unreachable")

    override fun exportManifest(manifest: ProbeManifest) = throw RuntimeException("collector unreachable")

    override fun exportStaticBaseline(baseline: StaticBaseline) = throw RuntimeException("collector unreachable")
}

/**
 * Records every call [ExportScheduler.maybeSweep] makes into the sweep, instead of doing any real
 * work, so a test can pin the cadence the two directions ADR 0027 and ADR 0028 keep without
 * driving ten real flush intervals or a real classpath walk.
 */
private class RecordingSweep(
    instrumentation: Instrumentation,
    registry: ProbeRegistry,
    config: AgentConfig,
    /** Runs on every call, standing in for work the real sweep does, such as counting dependencies. */
    private val onRun: () -> Unit = {},
) : LoadedClassSweep(instrumentation, registry, config) {
    data class Call(
        val runForwardPass: Boolean,
        val final: Boolean,
    )

    val calls = mutableListOf<Call>()

    override fun run(
        runForwardPass: Boolean,
        final: Boolean,
    ) {
        calls += Call(runForwardPass, final)
        onRun()
    }
}

class ExportSchedulerTest {
    private val config = AgentConfig.parse("serviceName=checkout,serviceVersion=1.0.0,serviceInstanceId=instance-1,environment=test")
    private val resource = TestResources.forConfig(config)

    @Test
    fun `flush sends the delta batch even when there is nothing new to report, as a liveness heartbeat`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        scheduler.flush()

        assertEquals(1, exporter.deltaBatches.size)
        assertTrue(
            exporter.deltaBatches
                .single()
                .deltas
                .isEmpty(),
        )
    }

    @Test
    fun `a successful flush advances the registry baseline`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        probes[0] += 4
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        scheduler.flush()

        assertEquals(1, exporter.deltaBatches.size)
        assertEquals(
            4L,
            exporter.deltaBatches
                .single()
                .deltas
                .single()
                .hitsTotal,
        )
        assertTrue(
            registry
                .computeDeltaBatch(resource)
                .batch.deltas
                .isEmpty(),
        )
    }

    @Test
    fun `a failed flush leaves the baseline where it was so the next attempt retries everything`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        probes[0] += 4
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), FailingExporter())

        scheduler.flush()

        assertEquals(
            4L,
            registry
                .computeDeltaBatch(resource)
                .batch
                .deltas
                .single()
                .hitsTotal,
        )
    }

    @Test
    fun `the first flush sends the manifest, even with nothing new to report`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        scheduler.flush()

        assertEquals(1, exporter.manifests.size)
        assertEquals(
            "com.example.Foo",
            exporter.manifests
                .single()
                .probes
                .single()
                .className,
        )
        assertEquals(
            "instance-1",
            exporter.manifests
                .single()
                .resource.serviceInstanceId,
            "the manifest must carry an instance to key on, since class_id is assigned " +
                "independently per instance and can mean a different class in another one",
        )
    }

    @Test
    fun `the manifest is not resent once its probes have already been included`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        scheduler.flush()
        scheduler.flush()
        scheduler.flush()

        assertEquals(1, exporter.manifests.size)
    }

    @Test
    fun `a class registered after an earlier successful flush is included in a later manifest send`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        scheduler.flush()
        registry.register("com.example.Bar", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "baz", "()V", 1)))
        scheduler.flush()

        assertEquals(2, exporter.manifests.size)
        assertEquals(
            "com.example.Bar",
            exporter.manifests[1]
                .probes
                .single()
                .className,
        )
    }

    @Test
    fun `a skipped class with no probes at all still triggers a manifest send`() {
        val registry = ProbeRegistry()
        registry.recordSkipped("com.example.Foo", reason = "annotation not supported on TYPE")
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        scheduler.flush()

        assertEquals(1, exporter.manifests.size)
        assertEquals(
            "com.example.Foo",
            exporter.manifests
                .single()
                .skippedClasses
                .single()
                .className,
        )
    }

    @Test
    fun `an exception thrown while computing the delta batch does not stop future flushes`() {
        val registry =
            object : ProbeRegistry() {
                override fun computeDeltaBatches(
                    resource: ResourceAttributes,
                    maxDeltasPerBatch: Int,
                ): List<ProbeRegistry.DeltaSnapshot> = throw RuntimeException("boom")
            }
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        // A ScheduledExecutorService running scheduleAtFixedRate stops calling a task forever,
        // the first time it lets an exception escape, with nothing logged. So flush() must never
        // let one out. This registry throws from computeDeltaBatch itself, to prove the guard
        // covers that call too, not only the exporter call.
        scheduler.flush()
        scheduler.flush()
    }

    @Test
    fun `an exception thrown while computing the manifest delta does not stop future flushes`() {
        val registry =
            object : ProbeRegistry() {
                override fun computeManifestDeltas(
                    resource: ResourceAttributes,
                    maxEntriesPerChunk: Int,
                ): List<ProbeRegistry.ManifestSnapshot> = throw RuntimeException("boom")
            }
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        scheduler.flush()
        scheduler.flush()

        assertEquals(
            2,
            exporter.deltaBatches.size,
            "the delta batch send should still happen on every flush even if the manifest side failed",
        )
    }

    @Test
    fun `a failed manifest send is retried on the next flush`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        var fail = true
        val exporter =
            object : Exporter {
                var manifestSends = 0

                override fun exportDeltaBatch(batch: DeltaBatch) {}

                override fun exportManifest(manifest: ProbeManifest) {
                    manifestSends++
                    if (fail) throw RuntimeException("collector unreachable")
                }

                override fun exportStaticBaseline(baseline: StaticBaseline) {}
            }
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        scheduler.flush()
        assertEquals(1, exporter.manifestSends)

        fail = false
        scheduler.flush()
        assertEquals(2, exporter.manifestSends)

        scheduler.flush()
        assertEquals(2, exporter.manifestSends)
    }

    @Test
    fun `a flush sends the manifest and deltas in capped chunks, advancing each one as it is confirmed`() {
        val registry = ProbeRegistry()
        for (name in listOf("Foo", "Bar", "Baz")) {
            val probes = registry.register("com.example.$name", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))
            probes[0] = 1
        }
        val exporter = RecordingExporter()
        // Each class weighs 2 in the manifest cap (1 probe + 0 edges + 1 for its own
        // ClassLocation record, ADR 0024), so the cap is 4, not 2, to keep two classes per
        // chunk: 2 + 2 = 4 fits, and a third class's own 2 would push it past the cap.
        val scheduler =
            ExportScheduler(config, resource, registry, EndpointRegistry(), exporter, maxDeltasPerBatch = 2, maxManifestEntriesPerChunk = 4)

        scheduler.flush()

        assertEquals(listOf(2, 1), exporter.deltaBatches.map { it.deltas.size })
        assertEquals(listOf(2, 1), exporter.manifests.map { it.probes.size })
        assertTrue(
            registry
                .computeDeltaBatch(resource)
                .batch.deltas
                .isEmpty(),
        )
        assertTrue(
            registry
                .computeManifestDelta(ResourceAttributes("checkout", null, "instance-1", null, "run-1"))
                .manifest.probes
                .isEmpty(),
        )
    }

    @Test
    fun `every delta batch and manifest chunk from one scheduler carries the resource it was given`() {
        val registry = ProbeRegistry()
        for (name in listOf("Foo", "Bar", "Baz")) {
            val probes = registry.register("com.example.$name", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))
            probes[0] = 1
        }
        val exporter = RecordingExporter()
        val scheduler =
            ExportScheduler(config, resource, registry, EndpointRegistry(), exporter, maxDeltasPerBatch = 1, maxManifestEntriesPerChunk = 1)

        scheduler.flush()
        scheduler.flush(final = true)

        val runIds = exporter.deltaBatches.map { it.resource.runId } + exporter.manifests.map { it.resource.runId }
        assertTrue(exporter.deltaBatches.size > 1 && exporter.manifests.size > 1, "the caps must split both payloads")
        assertEquals(setOf(TestResources.RUN_ID), runIds.toSet(), "one scheduler must stamp one run id: $runIds")
        assertEquals(setOf(resource), (exporter.deltaBatches.map { it.resource } + exporter.manifests.map { it.resource }).toSet())
    }

    @Test
    fun `a new run gets a new run id under the same configured instance id`() {
        val first = ResourceAttributes.forNewRun(config)
        val second = ResourceAttributes.forNewRun(config)

        assertEquals(first.serviceInstanceId, second.serviceInstanceId)
        assertTrue(first.runId.isNotEmpty() && second.runId.isNotEmpty())
        assertNotEquals(first.runId, second.runId, "two runs must never share a run id")
    }

    @Test
    fun `a chunk that fails mid-way leaves only the unconfirmed chunks pending for the next flush`() {
        val registry = ProbeRegistry()
        for (name in listOf("Foo", "Bar", "Baz")) {
            val probes = registry.register("com.example.$name", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))
            probes[0] = 1
        }
        val exporter =
            object : Exporter {
                var deltaSends = 0
                var manifestSends = 0

                override fun exportDeltaBatch(batch: DeltaBatch) {
                    if (++deltaSends == 2) throw RuntimeException("collector unreachable")
                }

                override fun exportManifest(manifest: ProbeManifest) {
                    if (++manifestSends == 2) throw RuntimeException("collector unreachable")
                }

                override fun exportStaticBaseline(baseline: StaticBaseline) {}
            }
        val scheduler =
            ExportScheduler(config, resource, registry, EndpointRegistry(), exporter, maxDeltasPerBatch = 1, maxManifestEntriesPerChunk = 1)

        scheduler.flush()

        assertEquals(2, exporter.deltaSends, "the loop stops at the first failure")
        assertEquals(
            2,
            registry
                .computeDeltaBatch(resource)
                .batch.deltas.size,
            "the failed chunk and the unsent one remain",
        )
        assertEquals(
            2,
            registry
                .computeManifestDelta(ResourceAttributes("checkout", null, "instance-1", null, "run-1"))
                .manifest.probes.size,
        )
    }

    /** Returns 0 from every `nextLong(bound)`, so the first scheduled flush runs immediately on start(). */
    private val noJitter =
        object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
        }

    /** Blocks every delta send on [gate] until released, and counts how many sends were attempted. */
    private class GatedExporter : Exporter {
        val gate = CountDownLatch(1)
        val deltaSends = AtomicInteger(0)
        val inFlight = CountDownLatch(1)

        override fun exportDeltaBatch(batch: DeltaBatch) {
            deltaSends.incrementAndGet()
            inFlight.countDown()
            gate.await()
        }

        override fun exportManifest(manifest: ProbeManifest) {}

        override fun exportStaticBaseline(baseline: StaticBaseline) {}
    }

    @Test
    fun `flushOnShutdown waits for an in-flight scheduled flush before running the final one`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        val exporter = GatedExporter()
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter, noJitter)
        scheduler.start()
        assertTrue(exporter.inFlight.await(5, TimeUnit.SECONDS), "the first scheduled flush should start immediately")

        val hook = thread { scheduler.flushOnShutdown(Duration.ofSeconds(10)) }
        Thread.sleep(200)
        assertEquals(1, exporter.deltaSends.get(), "the final flush must not start while a scheduled flush is in flight")

        exporter.gate.countDown()
        hook.join(10_000)

        assertEquals(2, exporter.deltaSends.get(), "exactly one final flush after the in-flight one finished")
    }

    @Test
    fun `flushOnShutdown skips the final flush when the in-flight flush uses up the budget`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        val exporter = GatedExporter()
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter, noJitter)
        scheduler.start()
        assertTrue(exporter.inFlight.await(5, TimeUnit.SECONDS))

        val elapsed = measureTimeMillis { scheduler.flushOnShutdown(Duration.ofMillis(300)) }
        exporter.gate.countDown()

        assertTrue(elapsed < 5_000, "the hook must return once the budget is spent, not wait for the hung flush")
        assertEquals(1, exporter.deltaSends.get(), "no final flush once the budget is gone")
    }

    @Test
    fun `flushOnShutdown with no scheduler started still runs one final flush`() {
        val registry = ProbeRegistry()
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        scheduler.flushOnShutdown(Duration.ofSeconds(5))

        assertEquals(1, exporter.deltaBatches.size)
    }

    @Test
    fun `flushOnShutdown returns at once when the budget is already spent, instead of joining the final flush forever`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        val exporter = GatedExporter()
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        // A zero budget is what the hook is left with when an in-flight flush finishes with less
        // than a millisecond to spare: the remaining time truncates to 0, and Thread.join(0)
        // means "wait forever", not "do not wait".
        val hook = thread { scheduler.flushOnShutdown(Duration.ZERO) }
        hook.join(3_000)
        val returned = !hook.isAlive
        exporter.gate.countDown()

        assertTrue(returned, "a spent budget must not turn into an unbounded wait on the final flush")
        assertEquals(0, exporter.deltaSends.get(), "no final flush is attempted once the budget is gone")
    }

    @Test
    fun `flush sends every delta batch with finalFlush false`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        probes[0] += 4
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        scheduler.flush()

        assertEquals(false, exporter.deltaBatches.single().finalFlush)
    }

    @Test
    fun `flushOnShutdown marks the empty heartbeat batch as the final flush`() {
        val registry = ProbeRegistry()
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        scheduler.flushOnShutdown(Duration.ofSeconds(5))

        val batch = exporter.deltaBatches.single()
        assertTrue(batch.deltas.isEmpty(), "nothing changed, so this is still the liveness heartbeat")
        assertTrue(batch.finalFlush)
    }

    @Test
    fun `flushOnShutdown marks every batch of its flush as final, standalone endpoint batch included`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        probes[0] += 1
        val endpointRegistry = EndpointRegistry()
        endpointRegistry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health").hit()
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, endpointRegistry, exporter, maxDeltasPerBatch = 1)

        scheduler.flushOnShutdown(Duration.ofSeconds(5))

        assertEquals(2, exporter.deltaBatches.size, "the probe batch is already full, so the endpoint delta gets its own")
        assertTrue(exporter.deltaBatches.all { it.finalFlush }, "every batch this flush sends must carry the final flush marker")
    }

    @Test
    fun `a second plain flush call still sends finalFlush false`() {
        val registry = ProbeRegistry()
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter)

        scheduler.flush()
        scheduler.flush()

        assertEquals(listOf(false, false), exporter.deltaBatches.map { it.finalFlush })
    }

    /** Counts down [delivered] on the first delta batch, so a test can wait on a scheduled tick from another thread. */
    private class LatchExporter : Exporter {
        val delivered = CountDownLatch(1)

        override fun exportDeltaBatch(batch: DeltaBatch) {
            delivered.countDown()
        }

        override fun exportManifest(manifest: ProbeManifest) {}

        override fun exportStaticBaseline(baseline: StaticBaseline) {}
    }

    @Test
    fun `an Error thrown by a send does not stop the schedule`() {
        var calls = 0
        val registry =
            object : ProbeRegistry() {
                override fun computeDeltaBatches(
                    resource: ResourceAttributes,
                    maxDeltasPerBatch: Int,
                ): List<ProbeRegistry.DeltaSnapshot> {
                    // A LinkageError is the realistic shape: a class the shaded jar failed to
                    // carry, first touched on the export path rather than at startup.
                    if (calls++ == 0) throw NoClassDefFoundError("com/example/Missing")
                    return super.computeDeltaBatches(resource, maxDeltasPerBatch)
                }
            }
        val exporter = LatchExporter()
        val oneSecond = AgentConfig.parse("serviceName=checkout,flushIntervalSeconds=1")
        val scheduler = ExportScheduler(oneSecond, TestResources.forConfig(oneSecond), registry, EndpointRegistry(), exporter, noJitter)

        scheduler.start()
        try {
            assertTrue(
                exporter.delivered.await(5, TimeUnit.SECONDS),
                "the second tick must still flush after the first tick's send threw an Error",
            )
        } finally {
            scheduler.stop()
        }
    }

    @Test
    fun `endpoint deltas ride in the same batch as probe deltas when there is room`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        probes[0] += 1
        val endpointRegistry = EndpointRegistry()
        endpointRegistry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health").hit()
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, endpointRegistry, exporter)

        scheduler.flush()

        assertEquals(1, exporter.deltaBatches.size, "one batch, not one per source, when everything fits")
        val batch = exporter.deltaBatches.single()
        assertEquals(1, batch.deltas.size)
        assertEquals(1, batch.endpointDeltas.size)
    }

    @Test
    fun `an endpoint snapshot that does not fit becomes its own delta batch`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        probes[0] += 1
        val endpointRegistry = EndpointRegistry()
        endpointRegistry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health").hit()
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, endpointRegistry, exporter, maxDeltasPerBatch = 1)

        scheduler.flush()

        assertEquals(2, exporter.deltaBatches.size, "the probe batch is already full, so the endpoint delta gets its own")
        assertEquals(listOf(1, 0), exporter.deltaBatches.map { it.deltas.size })
        assertEquals(listOf(0, 1), exporter.deltaBatches.map { it.endpointDeltas.size })
    }

    @Test
    fun `a flush with only endpoint activity still sends exactly one delta batch, carrying the heartbeat and the endpoint deltas`() {
        val registry = ProbeRegistry()
        val endpointRegistry = EndpointRegistry()
        endpointRegistry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health").hit()
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, endpointRegistry, exporter)

        scheduler.flush()

        assertEquals(1, exporter.deltaBatches.size)
        val batch = exporter.deltaBatches.single()
        assertTrue(batch.deltas.isEmpty(), "no probe changed, so the batch still carries the liveness heartbeat")
        assertEquals(1, batch.endpointDeltas.size)
    }

    @Test
    fun `a failed delta send leaves endpoint counts unadvanced, so the next flush reports them again`() {
        val registry = ProbeRegistry()
        val endpointRegistry = EndpointRegistry()
        endpointRegistry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health").hit()
        val scheduler = ExportScheduler(config, resource, registry, endpointRegistry, FailingExporter())

        scheduler.flush()

        assertEquals(
            1,
            endpointRegistry
                .computeDeltas(maxPerBatch = 10)
                .single()
                .deltas.size,
            "the failed send must not advance the endpoint delta baseline",
        )
    }

    @Test
    fun `endpoint manifest entries ride in the class manifest chunk when there is room`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        val endpointRegistry = EndpointRegistry()
        endpointRegistry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, endpointRegistry, exporter)

        scheduler.flush()

        assertEquals(1, exporter.manifests.size, "one manifest, not one per source, when everything fits")
        val manifest = exporter.manifests.single()
        assertEquals(1, manifest.probes.size)
        assertEquals(1, manifest.endpoints.size)
    }

    @Test
    fun `endpoint manifest entries stand alone when there are no class chunks to ride on`() {
        val registry = ProbeRegistry()
        val endpointRegistry = EndpointRegistry()
        endpointRegistry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, endpointRegistry, exporter)

        scheduler.flush()

        assertEquals(1, exporter.manifests.size)
        val manifest = exporter.manifests.single()
        assertTrue(manifest.probes.isEmpty())
        assertEquals(1, manifest.endpoints.size)
        assertEquals("instance-1", manifest.resource.serviceInstanceId)
    }

    @Test
    fun `disabled endpoint modules reach the wire`() {
        val registry = ProbeRegistry()
        val endpointRegistry = EndpointRegistry()
        endpointRegistry.recordDisabledModule("spring-mvc", reason = "linkage error against an unexpected framework version")
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, resource, registry, endpointRegistry, exporter)

        scheduler.flush()

        assertEquals(
            "spring-mvc",
            exporter.manifests
                .single()
                .disabledEndpointModules
                .single()
                .module,
        )
    }

    private fun DependencyRegistry.registerStartup(vararg artifacts: String) {
        for (artifact in artifacts) {
            register(
                listOf(DependencyIdentity("g", artifact, "1")),
                DependencyIdentitySource.POM_PROPERTIES,
                "/libs/$artifact.jar",
                DependencyDiscoverySource.STARTUP_CLASSPATH,
                classCount = 1,
            )
        }
    }

    /** A registry holding [artifacts], each counted with its counts delivered, so each is sendable at once. */
    private fun dependencyRegistryWith(vararg artifacts: String): DependencyRegistry =
        DependencyRegistry().apply {
            registerStartup(*artifacts)
            markCounted()
            markCountsDelivered(countGeneration)
        }

    @Test
    fun `dependencies ride on the class manifest chunk when there is room, and go out once`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        val exporter = RecordingExporter()
        val scheduler =
            ExportScheduler(config, resource, registry, EndpointRegistry(), exporter, dependencyRegistry = dependencyRegistryWith("a", "b"))

        scheduler.flush()
        scheduler.flush()

        assertEquals(1, exporter.manifests.size, "the second flush has nothing new to send")
        val manifest = exporter.manifests.single()
        assertEquals(1, manifest.probes.size)
        assertEquals(listOf("a", "b"), manifest.dependencies.map { it.identities.single().artifactId })
    }

    @Test
    fun `dependencies stand alone when there are no class chunks, and share a standalone endpoint manifest with room`() {
        val endpointRegistry = EndpointRegistry()
        endpointRegistry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health")
        val exporter = RecordingExporter()
        val scheduler =
            ExportScheduler(config, resource, ProbeRegistry(), endpointRegistry, exporter, dependencyRegistry = dependencyRegistryWith("a"))

        scheduler.flush()

        val manifest = exporter.manifests.single()
        assertTrue(manifest.probes.isEmpty())
        assertEquals(1, manifest.endpoints.size)
        assertEquals(1, manifest.dependencies.size)
        assertEquals("instance-1", manifest.resource.serviceInstanceId)
    }

    @Test
    fun `a failed manifest send leaves dependencies undelivered, so the next flush sends them again`() {
        val dependencyRegistry = dependencyRegistryWith("a")
        val failing =
            ExportScheduler(
                config,
                resource,
                ProbeRegistry(),
                EndpointRegistry(),
                FailingExporter(),
                dependencyRegistry = dependencyRegistry,
            )
        failing.flush()

        val exporter = RecordingExporter()
        ExportScheduler(config, resource, ProbeRegistry(), EndpointRegistry(), exporter, dependencyRegistry = dependencyRegistry).flush()

        assertEquals(
            1,
            exporter.manifests
                .single()
                .dependencies.size,
        )
    }

    @Test
    fun `packing dependencies onto a class chunk that is nearly full respects the cap`() {
        val registry = ProbeRegistry()
        val probes = (1..4).map { ProbeMeta(ProbeKind.METHOD, "m$it", "()V", 1) }
        registry.register("com.example.Foo", 1L, probes)
        val exporter = RecordingExporter()
        // The class weighs 5 (4 probes + its ClassLocation record), one short of the cap of 6.
        // The three dependencies form one chunk of weight 3, which does not fit beside the class
        // and so goes out on its own manifest.
        val scheduler =
            ExportScheduler(
                config,
                resource,
                registry,
                EndpointRegistry(),
                exporter,
                maxManifestEntriesPerChunk = 6,
                dependencyRegistry = dependencyRegistryWith("a", "b", "c"),
            )

        scheduler.flush()

        assertEquals(listOf(0, 3), exporter.manifests.map { it.dependencies.size })
        for (manifest in exporter.manifests) {
            val weight =
                manifest.probes.size + manifest.probes.sumOf { it.calls.size } + manifest.classLocations.size +
                    manifest.skippedClasses.size + manifest.unreportedClasses.size + manifest.endpoints.size +
                    manifest.disabledEndpointModules.size + manifest.dependencies.size
            assertTrue(weight <= 6, "a sent manifest must not exceed the cap it was chunked under, got $weight")
        }
    }

    /**
     * Sends two class chunks, one standalone rider manifest and, on a second flush, a manifest
     * holding only a late class, all under [agentConfig], and returns every manifest sent.
     */
    private fun manifestsSentUnder(agentConfig: AgentConfig): List<ProbeManifest> {
        val registry = ProbeRegistry()
        registry.register("com.example.A", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "a", "()V", 1)))
        registry.register("com.example.B", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "b", "()V", 1)))
        val exporter = RecordingExporter()
        val scheduler =
            ExportScheduler(
                agentConfig,
                TestResources.forConfig(agentConfig),
                registry,
                EndpointRegistry(),
                exporter,
                maxManifestEntriesPerChunk = 3,
                dependencyRegistry = dependencyRegistryWith("a", "b", "c"),
            )
        scheduler.flush()
        registry.register("com.example.C", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "c", "()V", 1)))
        scheduler.flush()
        return exporter.manifests
    }

    @Test
    fun `every manifest sent says references are recorded when include rules are set, standalone rider manifests included`() {
        val manifests = manifestsSentUnder(AgentConfig.parse("serviceName=checkout,includePackages=com.example"))

        assertTrue(
            manifests.any { it.probes.isEmpty() && it.dependencies.isNotEmpty() },
            "the scenario must send a standalone rider manifest",
        )
        assertTrue(manifests.count { it.probes.isNotEmpty() } >= 3, "the scenario must send several class chunks")
        assertTrue(manifests.all { it.referencesRecorded }, "every sent manifest must carry referencesRecorded = true")
    }

    @Test
    fun `every manifest sent says references are not recorded when no include rules are set`() {
        val manifests = manifestsSentUnder(config)

        assertTrue(
            manifests.any { it.probes.isEmpty() && it.dependencies.isNotEmpty() },
            "the scenario must send a standalone rider manifest",
        )
        assertTrue(manifests.none { it.referencesRecorded }, "no sent manifest may carry referencesRecorded = true")
    }

    private fun externalClassRegistryWith(vararg classNames: String): ExternalClassRegistry =
        ExternalClassRegistry({ true }, { 7 }).apply {
            for (name in classNames) record(name, "jar:file:/libs/lib.jar!/")
        }

    @Test
    fun `external classes ride on the class manifest chunk and go out once`() {
        val registry = ProbeRegistry()
        registry.register(
            "com.example.Foo",
            1L,
            listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1, referencedClasses = listOf("org.lib.A"))),
        )
        val exporter = RecordingExporter()
        val scheduler =
            ExportScheduler(
                config,
                resource,
                registry,
                EndpointRegistry(),
                exporter,
                externalClassRegistry = externalClassRegistryWith("org.lib.A"),
            )

        scheduler.flush()
        scheduler.flush()

        assertEquals(1, exporter.manifests.size, "the second flush has nothing new to send")
        assertEquals(listOf(ExternalClass("org.lib.A", 7)), exporter.manifests.single().externalClasses)
    }

    @Test
    fun `a failed manifest send leaves external classes undelivered, so the next flush sends them again`() {
        val externalClassRegistry = externalClassRegistryWith("org.lib.A")
        ExportScheduler(
            config,
            resource,
            ProbeRegistry(),
            EndpointRegistry(),
            FailingExporter(),
            externalClassRegistry = externalClassRegistry,
        ).flush()

        val exporter = RecordingExporter()
        ExportScheduler(
            config,
            resource,
            ProbeRegistry(),
            EndpointRegistry(),
            exporter,
            externalClassRegistry = externalClassRegistry,
        ).flush()

        assertEquals(
            1,
            exporter.manifests
                .single()
                .externalClasses.size,
        )
    }

    @Test
    fun `packing external classes counts a chunk's references, so the cap holds`() {
        val registry = ProbeRegistry()
        registry.register(
            "com.example.Foo",
            1L,
            listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1, referencedClasses = listOf("org.lib.A", "org.lib.B"))),
            classReferences = listOf("org.lib.C"),
        )
        val exporter = RecordingExporter()
        // The class weighs 5 (1 probe, 2 method references, its class location record, 1 class
        // reference). Two external classes do not fit beside it under a cap of 6.
        val scheduler =
            ExportScheduler(
                config,
                resource,
                registry,
                EndpointRegistry(),
                exporter,
                maxManifestEntriesPerChunk = 6,
                externalClassRegistry = externalClassRegistryWith("org.lib.A", "org.lib.B"),
            )

        scheduler.flush()

        assertEquals(listOf(0, 2), exporter.manifests.map { it.externalClasses.size })
        for (manifest in exporter.manifests) {
            val weight =
                manifest.probes.size + manifest.probes.sumOf { it.calls.size + it.referencedClasses.size } +
                    manifest.classLocations.size + manifest.classReferences.sumOf { it.referencedClasses.size } +
                    manifest.externalClasses.size
            assertTrue(weight <= 6, "a sent manifest must not exceed the cap it was chunked under, got $weight")
        }
    }

    /**
     * Captures the records a [java.lang.System.Logger] obtained for [loggerName] emits, through
     * its default `java.util.logging` backend. `System.Logger` delegates to `j.u.l.Logger` when no
     * custom `System.LoggerFinder` is installed, which is the case in this project's own tests.
     */
    private fun captureLogRecords(
        loggerName: String,
        block: () -> Unit,
    ): List<LogRecord> {
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() {}

                override fun close() {}
            }
        val julLogger = JulLogger.getLogger(loggerName)
        val originalLevel = julLogger.level
        julLogger.addHandler(handler)
        julLogger.level = JulLevel.ALL
        try {
            block()
        } finally {
            julLogger.removeHandler(handler)
            julLogger.level = originalLevel
        }
        return records
    }

    @Test
    fun `the first flush that finds dropped branch sites logs one INFO summary`() {
        val branchDropCounts = BranchDropCounts()
        branchDropCounts.record(mapOf(BranchDropReason.INLINED_OUT_OF_SCOPE to 3))
        val scheduler =
            ExportScheduler(config, resource, ProbeRegistry(), EndpointRegistry(), RecordingExporter(), branchDropCounts = branchDropCounts)

        val records = captureLogRecords(ExportScheduler::class.java.name) { scheduler.flush() }

        val summary = records.filter { it.message.contains("branch sites") }
        assertEquals(1, summary.size)
        assertEquals(JulLevel.INFO, summary.single().level)
        assertTrue(summary.single().message.contains("3"))
        assertTrue(summary.single().message.contains("inlined from out-of-scope code"))
    }

    @Test
    fun `the first-flush INFO summary names both drop reasons with their own counts`() {
        val branchDropCounts = BranchDropCounts()
        branchDropCounts.record(mapOf(BranchDropReason.INLINED_OUT_OF_SCOPE to 3, BranchDropReason.COROUTINE_MACHINERY to 7))
        val scheduler =
            ExportScheduler(config, resource, ProbeRegistry(), EndpointRegistry(), RecordingExporter(), branchDropCounts = branchDropCounts)

        val records = captureLogRecords(ExportScheduler::class.java.name) { scheduler.flush() }

        val summary = records.single { it.message.contains("branch sites") }
        assertTrue(summary.message.contains("3 inlined from out-of-scope code"))
        assertTrue(summary.message.contains("7 coroutine machinery"))
    }

    @Test
    fun `the branch drop summary is logged only once, not on a second flush`() {
        val branchDropCounts = BranchDropCounts()
        branchDropCounts.record(mapOf(BranchDropReason.INLINED_OUT_OF_SCOPE to 1))
        val scheduler =
            ExportScheduler(config, resource, ProbeRegistry(), EndpointRegistry(), RecordingExporter(), branchDropCounts = branchDropCounts)

        val records =
            captureLogRecords(ExportScheduler::class.java.name) {
                scheduler.flush()
                scheduler.flush()
            }

        assertEquals(1, records.count { it.message.contains("branch sites") })
    }

    @Test
    fun `nothing is logged when no branch sites were dropped`() {
        val scheduler = ExportScheduler(config, resource, ProbeRegistry(), EndpointRegistry(), RecordingExporter())

        val records = captureLogRecords(ExportScheduler::class.java.name) { scheduler.flush() }

        assertTrue(records.none { it.message.contains("branch sites") })
    }

    @Test
    fun `the confirmation pass runs on every flush while the forward direction runs only on the tenth and the final flush`() {
        val registry = ProbeRegistry(confirmsDefinitions = true)
        registry.register("com.example.never.Loaded", layoutHash = 1L, probes = listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))
        val sweep = RecordingSweep(ByteBuddyAgent.install(), registry, config)
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), RecordingExporter(), loadedClassSweep = sweep)

        repeat(10) { scheduler.flush() }
        scheduler.flushOnShutdown(Duration.ofSeconds(5))

        assertEquals(
            11,
            sweep.calls.size,
            "a class awaits confirmation throughout, so every flush plus the final one must reach the sweep",
        )
        assertEquals(
            List(9) { false } + listOf(true, true),
            sweep.calls.map { it.runForwardPass },
            "only the tenth flush and the final flush run the forward, unreported-class direction",
        )
        assertEquals(List(10) { false } + listOf(true), sweep.calls.map { it.final })
    }

    @Test
    fun `the sweep walk is skipped entirely when nothing awaits confirmation and the forward direction is not due`() {
        val registry = ProbeRegistry(confirmsDefinitions = true)
        val sweep = RecordingSweep(ByteBuddyAgent.install(), registry, config)
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), RecordingExporter(), loadedClassSweep = sweep)

        scheduler.flush()

        assertTrue(sweep.calls.isEmpty(), "nothing awaits confirmation and this is neither the tenth nor the final flush")
    }

    @Test
    fun `the sweep walk is not skipped on an off-cadence flush when a class awaits confirmation`() {
        val registry = ProbeRegistry(confirmsDefinitions = true)
        registry.register("com.example.never.Loaded", layoutHash = 1L, probes = listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))
        val sweep = RecordingSweep(ByteBuddyAgent.install(), registry, config)
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), RecordingExporter(), loadedClassSweep = sweep)

        scheduler.flush()

        assertEquals(1, sweep.calls.size)
        assertEquals(false, sweep.calls.single().runForwardPass, "not yet the tenth or the final flush")
    }

    @Test
    fun `the sweep walk is not skipped on the tenth flush even when nothing awaits confirmation`() {
        val registry = ProbeRegistry(confirmsDefinitions = true)
        val sweep = RecordingSweep(ByteBuddyAgent.install(), registry, config)
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), RecordingExporter(), loadedClassSweep = sweep)

        repeat(9) { scheduler.flush() }
        assertTrue(sweep.calls.isEmpty(), "the first nine flushes have nothing to confirm and are not due")

        scheduler.flush()

        assertEquals(1, sweep.calls.size)
        assertTrue(sweep.calls.single().runForwardPass, "the tenth flush runs the forward direction regardless of confirmation state")
    }

    @Test
    fun `once the dependency listing completes the sweep runs on every flush, with the directions' cadences unchanged`() {
        val registry = ProbeRegistry(confirmsDefinitions = true)
        val sweep = RecordingSweep(ByteBuddyAgent.install(), registry, config)
        val dependencyRegistry = DependencyRegistry().apply { markListingComplete() }
        val scheduler =
            ExportScheduler(
                config,
                resource,
                registry,
                EndpointRegistry(),
                RecordingExporter(),
                loadedClassSweep = sweep,
                dependencyRegistry = dependencyRegistry,
            )

        repeat(10) { scheduler.flush() }
        scheduler.flushOnShutdown(Duration.ofSeconds(5))

        assertEquals(11, sweep.calls.size, "nothing awaits confirmation, but dependency counting needs the walk on every flush")
        assertEquals(List(9) { false } + listOf(true, true), sweep.calls.map { it.runForwardPass })
        assertEquals(List(10) { false } + listOf(true), sweep.calls.map { it.final })
    }

    private fun dependencyRegistryWithLoads(vararg artifacts: String): DependencyRegistry =
        dependencyRegistryWith(*artifacts).apply {
            markListingComplete()
            for (entry in entries()) recordLoaded(entry.dependencyId, "org.lib.Class${entry.dependencyId}")
        }

    @Test
    fun `dependency deltas ride on the probe batch when there is room`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        probes[0] += 1
        val exporter = RecordingExporter()
        val scheduler =
            ExportScheduler(
                config,
                resource,
                registry,
                EndpointRegistry(),
                exporter,
                dependencyRegistry = dependencyRegistryWithLoads("a", "b"),
            )

        scheduler.flush()
        scheduler.flush()

        assertEquals(2, exporter.deltaBatches.size, "one batch per flush")
        val first = exporter.deltaBatches.first()
        assertEquals(1, first.deltas.size)
        assertEquals(listOf(0, 1), first.dependencyDeltas.map { it.dependencyId })
        assertEquals(listOf(1L, 1L), first.dependencyDeltas.map { it.loadedClassesTotal })
        assertTrue(exporter.deltaBatches[1].dependencyDeltas.isEmpty(), "an unchanged total is not sent again")
    }

    @Test
    fun `dependency deltas that do not fit go on a standalone batch carrying final`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        probes[0] += 1
        val endpointRegistry = EndpointRegistry()
        endpointRegistry.register(key = Any(), framework = "http-server", verb = "GET", verbatimTemplate = "/health").hit()
        val exporter = RecordingExporter()
        val scheduler =
            ExportScheduler(
                config,
                resource,
                registry,
                endpointRegistry,
                exporter,
                maxDeltasPerBatch = 1,
                dependencyRegistry = dependencyRegistryWithLoads("a"),
            )

        scheduler.flushOnShutdown(Duration.ofSeconds(5))

        assertEquals(listOf(1, 0, 0), exporter.deltaBatches.map { it.deltas.size })
        assertEquals(listOf(0, 1, 0), exporter.deltaBatches.map { it.endpointDeltas.size })
        assertEquals(listOf(0, 0, 1), exporter.deltaBatches.map { it.dependencyDeltas.size })
        assertTrue(exporter.deltaBatches.all { it.finalFlush })
    }

    @Test
    fun `a failed delta send leaves dependency totals undelivered, so the next flush sends them again`() {
        val dependencyRegistry = dependencyRegistryWithLoads("a")
        ExportScheduler(
            config,
            resource,
            ProbeRegistry(),
            EndpointRegistry(),
            FailingExporter(),
            dependencyRegistry = dependencyRegistry,
        ).flush()

        val exporter = RecordingExporter()
        ExportScheduler(config, resource, ProbeRegistry(), EndpointRegistry(), exporter, dependencyRegistry = dependencyRegistry).flush()

        assertEquals(
            1,
            exporter.deltaBatches
                .single()
                .dependencyDeltas.size,
        )
    }

    @Test
    fun `packing an endpoint chunk onto a class chunk counts the class's call edges against the cap`() {
        val registry = ProbeRegistry()
        val edges = (1..3).map { CallEdge("com.example.Callee$it", "m", "()V", virtual = false) }
        registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1, calls = edges)))
        val endpointRegistry = EndpointRegistry()
        endpointRegistry.register(key = "k", framework = "fake", verb = "GET", verbatimTemplate = "/x")
        val exporter = RecordingExporter()
        // The class alone weighs 5 (1 probe + 3 edges + 1 for its own ClassLocation record),
        // exactly the cap, so the endpoint cannot share its chunk without overshooting.
        val scheduler = ExportScheduler(config, resource, registry, endpointRegistry, exporter, maxManifestEntriesPerChunk = 5)

        scheduler.flush()

        assertEquals(2, exporter.manifests.size, "the endpoint must go out in a chunk of its own")
        for (manifest in exporter.manifests) {
            val weight =
                manifest.probes.size + manifest.probes.sumOf { it.calls.size } + manifest.classLocations.size +
                    manifest.skippedClasses.size + manifest.unreportedClasses.size + manifest.endpoints.size +
                    manifest.disabledEndpointModules.size
            assertTrue(weight <= 5, "a sent manifest must not exceed the cap it was chunked under, got $weight")
        }
    }

    @Test
    fun `packing an endpoint chunk onto a class chunk counts the class's branch sites and line ranges against the cap`() {
        val registry = ProbeRegistry()
        val site =
            BranchSite(
                siteIndex = 0,
                siteKey = null,
                line = 1,
                outcomes =
                    listOf(
                        BranchOutcome(0, BranchRole.TAKEN, guardedLines = listOf(LineRange("A.kt", 2, 2))),
                        BranchOutcome(1, BranchRole.FALL_THROUGH, partlyGuardedLines = listOf(LineRange("A.kt", 1, 1))),
                    ),
            )
        registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1, branchSites = listOf(site))))
        val endpointRegistry = EndpointRegistry()
        endpointRegistry.register(key = "k", framework = "fake", verb = "GET", verbatimTemplate = "/x")
        val exporter = RecordingExporter()
        // The class alone weighs 7 (1 probe + 1 site + 2 outcomes + 2 line ranges + 1 for its own
        // ClassLocation record), exactly the cap, so the endpoint cannot share its chunk.
        val scheduler = ExportScheduler(config, resource, registry, endpointRegistry, exporter, maxManifestEntriesPerChunk = 7)

        scheduler.flush()

        assertEquals(2, exporter.manifests.size, "the endpoint must go out in a chunk of its own")
    }

    @Test
    fun `a sweep that throws is logged and the flush still sends, on that flush and the next`() {
        val registry = ProbeRegistry()
        val exporter = RecordingExporter()
        val sweep =
            object : LoadedClassSweep(ByteBuddyAgent.install(), registry, config) {
                override fun run(
                    runForwardPass: Boolean,
                    final: Boolean,
                ): Unit = throw IllegalStateException("the walk failed")
            }
        val scheduler = ExportScheduler(config, resource, registry, EndpointRegistry(), exporter, loadedClassSweep = sweep)

        scheduler.flush(final = true)
        scheduler.flush(final = true)

        assertEquals(2, exporter.deltaBatches.size, "the heartbeat goes out on both flushes")
    }

    /**
     * A scheduler over a startup listing of [artifacts], with a sweep that counts dependencies on
     * each run the way [io.github.lukedevops.yukon.dependencies.LoadedDependencyCounter] does, and
     * an external-class registry wired as the agent wires it. Every mapping resolves to [mappedTo].
     */
    private inner class ListingScenario(
        vararg artifacts: String,
        listingComplete: Boolean = true,
        mappedTo: Int = 0,
        maxDeltasPerBatch: Int = ExportScheduler.DEFAULT_MAX_DELTAS_PER_BATCH,
        val probeRegistry: ProbeRegistry = ProbeRegistry(),
    ) {
        val dependencyRegistry = DependencyRegistry().apply { registerStartup(*artifacts) }
        val externalClassRegistry =
            ExternalClassRegistry(dependencyRegistry::isListingComplete, { mappedTo }, dependencyRegistry::isSendable)
        val exporter = RecordingExporter()
        val sweep =
            RecordingSweep(ByteBuddyAgent.install(), probeRegistry, config) {
                if (dependencyRegistry.isListingComplete) dependencyRegistry.markCounted()
            }
        val scheduler =
            ExportScheduler(
                config,
                resource,
                probeRegistry,
                EndpointRegistry(),
                exporter,
                maxDeltasPerBatch = maxDeltasPerBatch,
                loadedClassSweep = sweep,
                dependencyRegistry = dependencyRegistry,
                externalClassRegistry = externalClassRegistry,
            )

        init {
            if (listingComplete) dependencyRegistry.markListingComplete()
        }

        /** Runs one flush and returns the manifests it sent. */
        fun flush(final: Boolean = false): List<ProbeManifest> {
            val before = exporter.manifests.size
            if (final) scheduler.flushOnShutdown(Duration.ofSeconds(5)) else scheduler.flush()
            return exporter.manifests.drop(before)
        }
    }

    @Test
    fun `the flush that delivers counts sends the released dependencies after its delta send is confirmed`() {
        val scenario = ListingScenario("a")
        scenario.probeRegistry.register("com.example.A", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "a", "()V", 1)))
        // The delta send waits until the main manifest send has gone out, so the dependency cannot
        // ride on it and only a later manifest send of the same flush can carry it.
        val mainManifestSent = CountDownLatch(1)
        scenario.exporter.afterManifestSend = { mainManifestSent.countDown() }
        scenario.exporter.beforeDeltaSend = { assertTrue(mainManifestSent.await(5, TimeUnit.SECONDS)) }

        scenario.flush()

        val sent = scenario.exporter.sent.toList()
        val delta = sent.indexOfFirst { it is DeltaBatch }
        val released = sent.indexOfFirst { it is ProbeManifest && it.dependencies.isNotEmpty() }
        assertTrue(released >= 0, "the dependency must go out in the flush that delivered its counts")
        assertTrue(delta in 0 until released, "the dependency went out before its counts were confirmed: $sent")
    }

    @Test
    fun `a failed delta send releases nothing, and the next successful flush does`() {
        val scenario = ListingScenario("a")
        scenario.exporter.failDeltaBatches = true

        val failed = scenario.flush()

        assertTrue(failed.none { it.dependencies.isNotEmpty() }, "counts that were never confirmed release nothing")
        scenario.exporter.failDeltaBatches = false
        assertEquals(listOf("a"), scenario.flush().flatMap { it.dependencies }.map { it.identities.single().artifactId })
    }

    @Test
    fun `a mapping held on a dependency goes out in the same flush as that dependency's entry`() {
        val scenario = ListingScenario("a")
        scenario.externalClassRegistry.record("org.lib.A", "jar:file:/libs/a.jar!/")
        scenario.externalClassRegistry.record("org.gone.Missing", null)
        scenario.exporter.failDeltaBatches = true

        val held = scenario.flush()

        assertEquals(listOf(ExternalClass("org.gone.Missing", null, absent = true)), held.flatMap { it.externalClasses })
        assertTrue(held.none { it.dependencies.isNotEmpty() })
        scenario.exporter.failDeltaBatches = false
        val released = scenario.flush()
        assertEquals(listOf(0), released.flatMap { it.dependencies }.map { it.dependencyId })
        assertEquals(listOf(ExternalClass("org.lib.A", 0)), released.flatMap { it.externalClasses })
    }

    @Test
    fun `dependenciesListed is false until the listing is delivered, then true on every manifest with no extra empty one`() {
        val scenario = ListingScenario("a", listingComplete = false)
        scenario.externalClassRegistry.record("org.lib.A", "jar:file:/libs/a.jar!/")
        scenario.probeRegistry.register("com.example.A", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "a", "()V", 1)))

        val beforeListing = scenario.flush()
        assertTrue(beforeListing.isNotEmpty() && beforeListing.none { it.dependenciesListed })

        scenario.dependencyRegistry.markListingComplete()
        val releasing = scenario.flush()
        assertTrue(releasing.dropLast(1).none { it.dependenciesListed }, "only the flush's last manifest may carry the flag")
        assertTrue(releasing.last().dependenciesListed, "the flush that delivers the listing must end with the flag")
        assertTrue(releasing.last().let { it.probes.isEmpty() && it.dependencies.isEmpty() && it.externalClasses.isEmpty() })
        assertEquals(1, releasing.sumOf { it.dependencies.size })
        assertEquals(1, releasing.sumOf { it.externalClasses.size })

        scenario.probeRegistry.register("com.example.B", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "b", "()V", 1)))
        val later = scenario.flush()
        assertEquals(1, later.size, "a manifest carrying the flag was confirmed, so no empty one follows")
        assertTrue(later.single().dependenciesListed)
        assertEquals(
            "com.example.B",
            later
                .single()
                .probes
                .single()
                .className,
        )
        assertTrue(scenario.flush().isEmpty())
    }

    @Test
    fun `a listing that completes with no dependencies sends the flag on the next flush, as an empty manifest`() {
        val scenario = ListingScenario()

        val first = scenario.flush()

        val manifest = first.single()
        assertTrue(manifest.dependenciesListed)
        assertTrue(manifest.probes.isEmpty() && manifest.dependencies.isEmpty() && manifest.externalClasses.isEmpty())
        assertTrue(scenario.flush().isEmpty())
    }

    @Test
    fun `a failed listing never sets dependenciesListed`() {
        val scenario = ListingScenario("a", listingComplete = false)
        scenario.dependencyRegistry.markListingFailed()
        scenario.probeRegistry.register("com.example.A", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "a", "()V", 1)))

        val manifests = scenario.flush() + scenario.flush() + scenario.flush(final = true)

        assertEquals(1, manifests.size)
        assertTrue(manifests.none { it.dependenciesListed })
    }

    @Test
    fun `the final flush delivers a listing counted in that same flush`() {
        val scenario = ListingScenario("a")

        val manifests = scenario.flush(final = true)

        assertEquals(1, manifests.sumOf { it.dependencies.size })
        assertTrue(manifests.last().dependenciesListed)
    }

    @Test
    fun `a flush whose first delta batch is confirmed and a later one fails releases nothing, and the next flush does`() {
        val scenario = ListingScenario("a", maxDeltasPerBatch = 1)
        for (name in listOf("com.example.A", "com.example.B")) {
            scenario.probeRegistry.register(name, 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))[0] += 1
        }
        scenario.exporter.failDeltaSendNumber = 2

        val split = scenario.flush()

        assertEquals(1, scenario.exporter.deltaBatches.size, "the first batch is confirmed and the second fails")
        assertTrue(split.none { it.dependencies.isNotEmpty() }, "a flush with a failed delta send releases nothing")
        assertEquals(1, scenario.flush().sumOf { it.dependencies.size })
    }

    @Test
    fun `a flush after the listing is delivered sends one manifest, even when a class registers during its delta send`() {
        val manifestComputed = AtomicReference(CountDownLatch(1))
        val probeRegistry =
            object : ProbeRegistry() {
                override fun computeManifestDeltas(
                    resource: ResourceAttributes,
                    maxEntriesPerChunk: Int,
                ): List<ProbeRegistry.ManifestSnapshot> =
                    super.computeManifestDeltas(resource, maxEntriesPerChunk).also { manifestComputed.get().countDown() }
            }
        val scenario = ListingScenario("a", probeRegistry = probeRegistry)
        scenario.flush()
        assertTrue(
            scenario.exporter.manifests
                .last()
                .dependenciesListed,
            "the first flush delivers the listing",
        )

        manifestComputed.set(CountDownLatch(1))
        probeRegistry.register("com.example.B", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "b", "()V", 1)))
        var registered = false
        scenario.exporter.beforeDeltaSend = {
            assertTrue(manifestComputed.get().await(5, TimeUnit.SECONDS))
            if (!registered) {
                registered = true
                probeRegistry.register("com.example.C", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "c", "()V", 1)))
            }
        }
        val second = scenario.flush()

        assertEquals(listOf("com.example.B"), second.flatMap { it.probes }.map { it.className }.distinct())
        assertEquals(1, second.size, "nothing was released, so no second manifest send runs")
    }

    @Test
    fun `an empty flag manifest that fails is sent again, but not by a flush whose delta send failed`() {
        val scenario = ListingScenario()
        var failFlag = true
        scenario.exporter.beforeManifestSend = { manifest ->
            if (manifest.dependenciesListed && failFlag) {
                failFlag = false
                throw RuntimeException("collector unreachable")
            }
        }

        assertTrue(scenario.flush().isEmpty(), "the flag manifest failed")

        scenario.exporter.failDeltaBatches = true
        assertTrue(scenario.flush().isEmpty(), "a flush whose delta send failed sends no flag manifest")

        scenario.exporter.failDeltaBatches = false
        val retried = scenario.flush().single()
        assertTrue(retried.dependenciesListed && retried.probes.isEmpty())
        assertTrue(scenario.flush().isEmpty())
    }

    @Test
    fun `a real sweep counts a listed jar's class, and its delta is confirmed before the manifest carrying its entry`(
        @TempDir dir: Path,
    ) {
        val includes = listOf("com.acme")
        val jar =
            TestJars.write(
                dir.resolve("listed-1.0.jar"),
                listOf(TestJars.pom("org.listed", "listed", "1.0"), TestJars.loadableClassEntry("org.listed.One")),
            )
        val dependencyRegistry = DependencyRegistry()
        Agent.runDependencyListing(StartupClasspathLister(includes, emptyList(), jar.toString())::list, dependencyRegistry)
        val listedId = assertNotNull(dependencyRegistry.idForKey(listOf("org.listed:listed")))
        val loaded = Class.forName("org.listed.One", true, URLClassLoader(arrayOf(jar.toUri().toURL()), null))
        val agentConfig = AgentConfig.parse("serviceName=checkout,includePackages=com.acme")
        val probeRegistry = ProbeRegistry()
        val exporter = RecordingExporter()
        val scheduler =
            ExportScheduler(
                agentConfig,
                TestResources.forConfig(agentConfig),
                probeRegistry,
                EndpointRegistry(),
                exporter,
                loadedClassSweep =
                    LoadedClassSweep(
                        ByteBuddyAgent.install(),
                        probeRegistry,
                        agentConfig,
                        LoadedDependencyCounter(dependencyRegistry, includes, emptyList()),
                    ),
                dependencyRegistry = dependencyRegistry,
            )

        scheduler.flush()
        Reference.reachabilityFence(loaded)

        val sent = exporter.sent.toList()
        val delta =
            sent.indexOfFirst { batch ->
                batch is DeltaBatch && batch.dependencyDeltas.any { it.dependencyId == listedId && it.loadedClassesTotal == 1L }
            }
        val entry = sent.indexOfFirst { it is ProbeManifest && it.dependencies.any { d -> d.dependencyId == listedId } }
        assertTrue(delta >= 0, "the counted class must go out on a confirmed delta batch")
        assertTrue(entry > delta, "the entry must follow its confirmed delta: delta at $delta, entry at $entry")
    }
}
