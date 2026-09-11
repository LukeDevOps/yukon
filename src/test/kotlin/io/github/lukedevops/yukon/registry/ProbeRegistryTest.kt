package io.github.lukedevops.yukon.registry

import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ResourceAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProbeRegistryTest {
    private val resource =
        ResourceAttributes(
            serviceName = "checkout",
            serviceVersion = "1.0.0",
            serviceInstanceId = "instance-1",
            environment = "test",
        )

    private fun methodProbes(count: Int): List<ProbeMeta> =
        (0 until count).map { ProbeMeta(ProbeKind.METHOD, "method$it", "()V", line = it) }

    @Test
    fun `register returns a zeroed array sized to the probe count`() {
        val registry = ProbeRegistry()

        val probes = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(3))

        assertEquals(3, probes.size)
        assertTrue(probes.all { it == 0L })
    }

    @Test
    fun `re-registering the same class and layout hash returns the same backing array`() {
        val registry = ProbeRegistry()

        val first = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(2))
        first[0]++
        val second = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(2))

        assertSame(first, second)
        assertEquals(1L, second[0])
    }

    @Test
    fun `a changed layout hash allocates a fresh array instead of merging`() {
        val registry = ProbeRegistry()

        val original = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(2))
        original[0]++
        val afterRedefine = registry.register("com.example.Foo", layoutHash = 2L, probes = methodProbes(4))

        assertEquals(4, afterRedefine.size)
        assertTrue(afterRedefine.all { it == 0L })
    }

    @Test
    fun `computeDeltaBatch reports only probes hit since the last baseline`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(3))
        probes[0] += 5
        probes[2] += 2

        val batch = registry.computeDeltaBatch(resource)

        assertEquals(2, batch.deltas.size)
        val byIndex = batch.deltas.associateBy { it.probeIndex }
        assertEquals(5L, byIndex.getValue(0).hitsSinceLastFlush)
        assertEquals(2L, byIndex.getValue(2).hitsSinceLastFlush)
        assertEquals(resource, batch.resource)
    }

    @Test
    fun `probes with no hits since baseline are omitted`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(3))

        val batch = registry.computeDeltaBatch(resource)

        assertTrue(batch.deltas.isEmpty())
    }

    @Test
    fun `advanceBaseline resets deltas for hits already reported`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        probes[0] += 3

        registry.computeDeltaBatch(resource)
        registry.advanceBaseline()
        val secondBatch = registry.computeDeltaBatch(resource)

        assertTrue(secondBatch.deltas.isEmpty())
    }

    @Test
    fun `hits recorded between a snapshot and a failed flush are not lost`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        probes[0] += 3

        // Flush computes a snapshot but the send fails, so advanceBaseline is
        // never called; more hits land before the next flush attempt.
        registry.computeDeltaBatch(resource)
        probes[0] += 2

        val retryBatch = registry.computeDeltaBatch(resource)

        assertEquals(5L, retryBatch.deltas.single().hitsSinceLastFlush)
    }

    @Test
    fun `advanceBaseline only advances to the last computed snapshot, not live counts`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        probes[0] += 3

        registry.computeDeltaBatch(resource) // snapshot = 3
        probes[0] += 2 // accrues after the snapshot, e.g. while the POST is in flight
        registry.advanceBaseline() // must advance to 3, not to the live value of 5

        val nextBatch = registry.computeDeltaBatch(resource)

        assertEquals(2L, nextBatch.deltas.single().hitsSinceLastFlush)
    }

    @Test
    fun `firstSeenAt is stamped once and stays stable across subsequent flushes`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        probes[0] += 1

        val firstBatch = registry.computeDeltaBatch(resource)
        val firstSeenAt = firstBatch.deltas.single().firstSeenAt
        registry.advanceBaseline()

        probes[0] += 1
        val secondBatch = registry.computeDeltaBatch(resource)

        assertEquals(firstSeenAt, secondBatch.deltas.single().firstSeenAt)
    }

    @Test
    fun `classId is assigned once per class and stays stable across re-registration`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        registry.register("com.example.Bar", layoutHash = 1L, probes = methodProbes(1))

        val fooProbes = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        fooProbes[0]++
        val delta = registry.computeDeltaBatch(resource).deltas.single()

        assertEquals(0, delta.classId)
    }

    @Test
    fun `manifest describes every registered probe location`() {
        val registry = ProbeRegistry()
        registry.register(
            "com.example.Foo",
            layoutHash = 1L,
            probes = listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", line = 10)),
        )

        val manifest = registry.manifest(serviceName = "checkout", serviceVersion = "1.0.0")

        val location = manifest.probes.single()
        assertEquals("com.example.Foo", location.className)
        assertEquals("bar", location.methodName)
        assertEquals(10, location.line)
        assertEquals(ProbeKind.METHOD, location.kind)
    }
}
