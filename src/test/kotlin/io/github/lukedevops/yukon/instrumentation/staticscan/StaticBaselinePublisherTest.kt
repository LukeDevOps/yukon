package io.github.lukedevops.yukon.instrumentation.staticscan

import io.github.lukedevops.yukon.export.DeclaredClass
import io.github.lukedevops.yukon.export.DeclaredMethod
import io.github.lukedevops.yukon.export.DeltaBatch
import io.github.lukedevops.yukon.export.Exporter
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ProbeManifest
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.export.StaticBaseline
import io.github.lukedevops.yukon.export.UnprobedClass
import io.github.lukedevops.yukon.registry.ProbeMeta
import io.github.lukedevops.yukon.registry.ProbeRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticBaselinePublisherTest {
    private val resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "test", "run-1")

    private class RecordingExporter(
        private val failOnChunk: Int? = null,
    ) : Exporter {
        val baselines = mutableListOf<StaticBaseline>()

        override fun exportDeltaBatch(batch: DeltaBatch) {}

        override fun exportManifest(manifest: ProbeManifest) {}

        override fun exportStaticBaseline(baseline: StaticBaseline) {
            if (baseline.chunkIndex == failOnChunk) throw RuntimeException("collector unreachable")
            baselines += baseline
        }
    }

    private fun scanOf(vararg classNames: String) =
        StaticScanResult(
            declaredClasses = classNames.map { DeclaredClass(it, listOf(DeclaredMethod("m", "()V"))) },
            staticallyUnsafeClasses = emptyList(),
            unreadableClasses = emptyList(),
        )

    @Test
    fun `sends every chunk of the scan in order`() {
        val exporter = RecordingExporter()
        val publisher = StaticBaselinePublisher({ scanOf("A", "B", "C") }, exporter, ProbeRegistry(), StaticBaselineMismatchDetector(), 1)

        publisher.run(resource)

        assertEquals(listOf(0, 1, 2), exporter.baselines.map { it.chunkIndex })
        assertTrue(exporter.baselines.all { it.chunkCount == 3 })
        assertEquals(listOf("A", "B", "C"), exporter.baselines.map { it.declaredClasses.single().className })
    }

    @Test
    fun `a failed chunk stops the send and swallows the failure`() {
        val exporter = RecordingExporter(failOnChunk = 1)
        val publisher = StaticBaselinePublisher({ scanOf("A", "B", "C") }, exporter, ProbeRegistry(), StaticBaselineMismatchDetector(), 1)

        publisher.run(resource)

        assertEquals(listOf(0), exporter.baselines.map { it.chunkIndex })
    }

    @Test
    fun `primes the mismatch detector with every class the scan saw, in any bucket`() {
        val detector = StaticBaselineMismatchDetector()
        val scan =
            StaticScanResult(
                declaredClasses = listOf(DeclaredClass("A", listOf(DeclaredMethod("m", "()V")))),
                staticallyUnsafeClasses = emptyList(),
                unreadableClasses = emptyList(),
                unprobedClasses = listOf(UnprobedClass("P", "no concrete methods to probe")),
            )
        val publisher = StaticBaselinePublisher({ scan }, RecordingExporter(), ProbeRegistry(), detector)

        publisher.run(resource)

        assertFalse(detector.shouldWarnAbout("A"))
        assertFalse(detector.shouldWarnAbout("P"), "a class the scan saw but did not declare is not a blind spot")
        assertTrue(detector.shouldWarnAbout("Z"))
    }

    @Test
    fun `sweeps classes that registered before the scan finished, so they are only warned about once`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Early", 1L, listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", 1)))
        val detector = StaticBaselineMismatchDetector()
        val publisher = StaticBaselinePublisher({ scanOf("A") }, RecordingExporter(), registry, detector)

        publisher.run(resource)

        // run() consumed the one-time warning for the early class; a later dynamic check must not repeat it.
        assertFalse(detector.shouldWarnAbout("com.example.Early"))
    }
}
