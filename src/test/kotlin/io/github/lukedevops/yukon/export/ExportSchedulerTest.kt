package io.github.lukedevops.yukon.export

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.registry.ProbeMeta
import io.github.lukedevops.yukon.registry.ProbeRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        val scheduler = ExportScheduler(config, registry, exporter)

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
        val scheduler = ExportScheduler(config, registry, exporter)

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
        assertTrue(registry.computeDeltaBatch(resource).deltas.isEmpty())
    }

    @Test
    fun `a failed flush leaves the baseline where it was so the next attempt retries everything`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", 1)))
        probes[0] += 4
        val scheduler = ExportScheduler(config, registry, FailingExporter())

        scheduler.flush()

        assertEquals(
            4L,
            registry
                .computeDeltaBatch(resource)
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
        val scheduler = ExportScheduler(config, registry, exporter)

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
        val scheduler = ExportScheduler(config, registry, exporter)

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
        val scheduler = ExportScheduler(config, registry, exporter)

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
        val scheduler = ExportScheduler(config, registry, exporter)

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
                override fun computeDeltaBatch(resource: ResourceAttributes): DeltaBatch = throw RuntimeException("boom")
            }
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, registry, exporter)

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
                override fun computeManifestDelta(
                    serviceName: String,
                    serviceVersion: String?,
                    serviceInstanceId: String,
                ): ProbeManifest = throw RuntimeException("boom")
            }
        val exporter = RecordingExporter()
        val scheduler = ExportScheduler(config, registry, exporter)

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
        val scheduler = ExportScheduler(config, registry, exporter)

        scheduler.flush()
        assertEquals(1, exporter.manifestSends)

        fail = false
        scheduler.flush()
        assertEquals(2, exporter.manifestSends)

        scheduler.flush()
        assertEquals(2, exporter.manifestSends)
    }
}
