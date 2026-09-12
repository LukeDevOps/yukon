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

    @Test
    fun `computeManifestDelta reports probes not yet included in a sent manifest`() {
        val registry = ProbeRegistry()
        registry.register(
            "com.example.Foo",
            layoutHash = 1L,
            probes = listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", line = 10)),
        )

        val delta = registry.computeManifestDelta(serviceName = "checkout", serviceVersion = "1.0.0")

        assertEquals("com.example.Foo", delta.probes.single().className)
    }

    @Test
    fun `advanceManifestBaseline marks reported classes so they are not sent again`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))

        registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null)
        registry.advanceManifestBaseline()
        val secondDelta = registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null)

        assertTrue(secondDelta.probes.isEmpty())
    }

    @Test
    fun `a class registered after the manifest baseline advances appears in the next delta`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null)
        registry.advanceManifestBaseline()

        registry.register("com.example.Bar", layoutHash = 1L, probes = methodProbes(1))
        val secondDelta = registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null)

        assertEquals("com.example.Bar", secondDelta.probes.single().className)
    }

    @Test
    fun `a failed manifest send is not advanced, so the next computeManifestDelta retries the same classes`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))

        registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null)
        // advanceManifestBaseline is never called here, simulating a failed send.
        val retryDelta = registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null)

        assertEquals("com.example.Foo", retryDelta.probes.single().className)
    }

    @Test
    fun `unregister removes a class so it no longer appears in the manifest`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))

        registry.unregister("com.example.Foo")

        assertTrue(registry.manifest(serviceName = "checkout", serviceVersion = null).probes.isEmpty())
    }

    @Test
    fun `unregister only removes the named class, leaving others intact`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        registry.register("com.example.Bar", layoutHash = 1L, probes = methodProbes(1))

        registry.unregister("com.example.Foo")

        assertEquals("com.example.Bar", registry.manifest(serviceName = "checkout", serviceVersion = null).probes.single().className)
    }

    @Test
    fun `recordSkipped adds a class to the manifest with no probes`() {
        val registry = ProbeRegistry()

        registry.recordSkipped("com.example.Foo", reason = "annotation not supported on TYPE")

        val manifest = registry.manifest(serviceName = "checkout", serviceVersion = null)
        val skipped = manifest.skippedClasses.single()
        assertEquals("com.example.Foo", skipped.className)
        assertEquals("annotation not supported on TYPE", skipped.reason)
        assertTrue(manifest.probes.isEmpty())
    }

    @Test
    fun `recordSkipped is idempotent, keeping the first reason and timestamp`() {
        val registry = ProbeRegistry()

        registry.recordSkipped("com.example.Foo", reason = "first reason")
        registry.recordSkipped("com.example.Foo", reason = "second reason")

        val skipped = registry.manifest(serviceName = "checkout", serviceVersion = null).skippedClasses.single()
        assertEquals("first reason", skipped.reason)
    }

    @Test
    fun `computeManifestDelta reports skipped classes not yet included in a sent manifest`() {
        val registry = ProbeRegistry()
        registry.recordSkipped("com.example.Foo", reason = "annotation not supported on TYPE")

        val delta = registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null)

        assertEquals("com.example.Foo", delta.skippedClasses.single().className)
    }

    @Test
    fun `advanceManifestBaseline marks a skipped class as sent so it is not repeated`() {
        val registry = ProbeRegistry()
        registry.recordSkipped("com.example.Foo", reason = "annotation not supported on TYPE")

        registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null)
        registry.advanceManifestBaseline()
        val secondDelta = registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null)

        assertTrue(secondDelta.skippedClasses.isEmpty())
    }

    @Test
    fun `a failed manifest send is not advanced, so the next computeManifestDelta retries the same skipped class`() {
        val registry = ProbeRegistry()
        registry.recordSkipped("com.example.Foo", reason = "annotation not supported on TYPE")

        registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null)
        // advanceManifestBaseline is never called here, simulating a failed send.
        val retryDelta = registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null)

        assertEquals("com.example.Foo", retryDelta.skippedClasses.single().className)
    }
}
