package io.github.lukedevops.yukon.export

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.instrumentation.branch.BranchDropCounts
import io.github.lukedevops.yukon.instrumentation.branch.BranchDropReason
import io.github.lukedevops.yukon.registry.EndpointRegistry
import io.github.lukedevops.yukon.registry.ProbeMeta
import io.github.lukedevops.yukon.registry.ProbeRegistry
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.logging.Level as JulLevel
import java.util.logging.Logger as JulLogger

private class RecordingExporter : Exporter {
    var deltaBatches = mutableListOf<DeltaBatch>()
    var manifests = mutableListOf<ProbeManifest>()
    var staticBaselines = mutableListOf<StaticBaseline>()

    override fun exportDeltaBatch(batch: DeltaBatch) {
        deltaBatches += batch
    }

    override fun exportManifest(manifest: ProbeManifest) {
        manifests += manifest
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

class ExportSchedulerTest {
    private val resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "test")
    private val config = AgentConfig.parse("serviceName=checkout,serviceVersion=1.0.0,serviceInstanceId=instance-1,environment=test")

    @Test
    fun `flush sends the delta batch even when there is nothing new to report, as a liveness heartbeat`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, registry, EndpointRegistry(), exporter)

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
        val scheduler = ExportScheduler(config, registry, EndpointRegistry(), exporter)

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
        val scheduler = ExportScheduler(config, registry, EndpointRegistry(), FailingExporter())

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
        val scheduler = ExportScheduler(config, registry, EndpointRegistry(), exporter)

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
            exporter.manifests.single().serviceInstanceId,
            "the manifest must carry an instance to key on, since class_id is assigned " +
                "independently per instance and can mean a different class in another one",
        )
    }

    @Test
    fun `the manifest is not resent once its probes have already been included`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, registry, EndpointRegistry(), exporter)

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
        val scheduler = ExportScheduler(config, registry, EndpointRegistry(), exporter)

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
        val scheduler = ExportScheduler(config, registry, EndpointRegistry(), exporter)

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
        val scheduler = ExportScheduler(config, registry, EndpointRegistry(), exporter)

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
                    serviceName: String,
                    serviceVersion: String?,
                    serviceInstanceId: String,
                    maxEntriesPerChunk: Int,
                ): List<ProbeRegistry.ManifestSnapshot> = throw RuntimeException("boom")
            }
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, registry, EndpointRegistry(), exporter)

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
        val scheduler = ExportScheduler(config, registry, EndpointRegistry(), exporter)

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
        // Each class now weighs 2 in the manifest cap (1 probe + 0 edges + 1 for its own
        // ClassSupertypes record, ADR 0024), so the cap is 4, not 2, to keep two classes per
        // chunk: 2 + 2 = 4 fits, and a third class's own 2 would push it past the cap.
        val scheduler =
            ExportScheduler(config, registry, EndpointRegistry(), exporter, maxDeltasPerBatch = 2, maxManifestEntriesPerChunk = 4)

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
                .computeManifestDelta("checkout", null, "instance-1")
                .manifest.probes
                .isEmpty(),
        )
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
            ExportScheduler(config, registry, EndpointRegistry(), exporter, maxDeltasPerBatch = 1, maxManifestEntriesPerChunk = 1)

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
                .computeManifestDelta("checkout", null, "instance-1")
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
        val scheduler = ExportScheduler(config, registry, EndpointRegistry(), exporter, noJitter)
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
        val scheduler = ExportScheduler(config, registry, EndpointRegistry(), exporter, noJitter)
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
        val scheduler = ExportScheduler(config, registry, EndpointRegistry(), exporter)

        scheduler.flushOnShutdown(Duration.ofSeconds(5))

        assertEquals(1, exporter.deltaBatches.size)
    }

    @Test
    fun `flushOnShutdown returns at once when the budget is already spent, instead of joining the final flush forever`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        val exporter = GatedExporter()
        val scheduler = ExportScheduler(config, registry, EndpointRegistry(), exporter)

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
        val scheduler = ExportScheduler(oneSecond, registry, EndpointRegistry(), exporter, noJitter)

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
        val scheduler = ExportScheduler(config, registry, endpointRegistry, exporter)

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
        val scheduler = ExportScheduler(config, registry, endpointRegistry, exporter, maxDeltasPerBatch = 1)

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
        val scheduler = ExportScheduler(config, registry, endpointRegistry, exporter)

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
        val scheduler = ExportScheduler(config, registry, endpointRegistry, FailingExporter())

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
        val scheduler = ExportScheduler(config, registry, endpointRegistry, exporter)

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
        val scheduler = ExportScheduler(config, registry, endpointRegistry, exporter)

        scheduler.flush()

        assertEquals(1, exporter.manifests.size)
        val manifest = exporter.manifests.single()
        assertTrue(manifest.probes.isEmpty())
        assertEquals(1, manifest.endpoints.size)
        assertEquals("instance-1", manifest.serviceInstanceId)
    }

    @Test
    fun `disabled endpoint modules reach the wire`() {
        val registry = ProbeRegistry()
        val endpointRegistry = EndpointRegistry()
        endpointRegistry.recordDisabledModule("spring-mvc", reason = "linkage error against an unexpected framework version")
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, registry, endpointRegistry, exporter)

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
            ExportScheduler(config, ProbeRegistry(), EndpointRegistry(), RecordingExporter(), branchDropCounts = branchDropCounts)

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
            ExportScheduler(config, ProbeRegistry(), EndpointRegistry(), RecordingExporter(), branchDropCounts = branchDropCounts)

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
            ExportScheduler(config, ProbeRegistry(), EndpointRegistry(), RecordingExporter(), branchDropCounts = branchDropCounts)

        val records =
            captureLogRecords(ExportScheduler::class.java.name) {
                scheduler.flush()
                scheduler.flush()
            }

        assertEquals(1, records.count { it.message.contains("branch sites") })
    }

    @Test
    fun `nothing is logged when no branch sites were dropped`() {
        val scheduler = ExportScheduler(config, ProbeRegistry(), EndpointRegistry(), RecordingExporter())

        val records = captureLogRecords(ExportScheduler::class.java.name) { scheduler.flush() }

        assertTrue(records.none { it.message.contains("branch sites") })
    }
}
