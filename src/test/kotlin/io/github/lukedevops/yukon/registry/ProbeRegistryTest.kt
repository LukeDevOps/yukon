package io.github.lukedevops.yukon.registry

import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ResourceAttributes
import java.net.URLClassLoader
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
    fun `the same class name loaded by two different classloaders gets two separate arrays`() {
        val registry = ProbeRegistry()
        val loaderA = URLClassLoader(emptyArray())
        val loaderB = URLClassLoader(emptyArray())

        val first = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1), classLoader = loaderA)
        first[0]++
        val second = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1), classLoader = loaderB)

        assertTrue(first !== second, "two different classloaders' same-named classes must not share a counts array")
        assertEquals(0L, second[0], "a hit recorded against one classloader's class must not appear against another's")
    }

    @Test
    fun `unregister for one classloader leaves another classloader's same-named class intact`() {
        val registry = ProbeRegistry()
        val loaderA = URLClassLoader(emptyArray())
        val loaderB = URLClassLoader(emptyArray())
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1), classLoader = loaderA)
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1), classLoader = loaderB)

        registry.unregister("com.example.Foo", loaderA)

        val remaining = registry.manifest(serviceName = "checkout", serviceVersion = null, serviceInstanceId = "instance-1").probes
        assertEquals(
            1,
            remaining.size,
            "unregistering one classloader's failed class must not remove another loader's successfully registered one",
        )
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
    fun `computeDeltaBatch reports the cumulative count for probes that changed since the last send`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(3))
        probes[0] += 5
        probes[2] += 2

        val batch = registry.computeDeltaBatch(resource).batch

        assertEquals(2, batch.deltas.size)
        val byIndex = batch.deltas.associateBy { it.probeIndex }
        assertEquals(5L, byIndex.getValue(0).hitsTotal)
        assertEquals(2L, byIndex.getValue(2).hitsTotal)
        assertEquals(resource, batch.resource)
    }

    @Test
    fun `probes with no hits since the last send are omitted`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(3))

        val batch = registry.computeDeltaBatch(resource).batch

        assertTrue(batch.deltas.isEmpty())
    }

    @Test
    fun `advanceBaseline omits probes whose count already reached the collector`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        probes[0] += 3

        registry.advanceBaseline(registry.computeDeltaBatch(resource))
        val secondBatch = registry.computeDeltaBatch(resource).batch

        assertTrue(secondBatch.deltas.isEmpty())
    }

    @Test
    fun `hits recorded between a snapshot and a failed flush are not lost`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        probes[0] += 3

        // Flush computes a snapshot, but the send fails. So advanceBaseline is never called.
        // More hits land before the next flush attempt.
        registry.computeDeltaBatch(resource)
        probes[0] += 2

        val retryBatch = registry.computeDeltaBatch(resource).batch

        assertEquals(5L, retryBatch.deltas.single().hitsTotal)
    }

    @Test
    fun `advanceBaseline only advances to the last computed snapshot, not live counts`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        probes[0] += 3

        val snapshot = registry.computeDeltaBatch(resource) // snapshot = 3
        probes[0] += 2 // accrues after the snapshot, e.g. while the POST is in flight
        registry.advanceBaseline(snapshot) // must advance to 3, not to the live value of 5

        val nextBatch = registry.computeDeltaBatch(resource).batch

        // The live count (5) is what gets reported once it is next seen as changed, since
        // hitsTotal is the current cumulative count, not a delta computed against 3.
        assertEquals(5L, nextBatch.deltas.single().hitsTotal)
    }

    @Test
    fun `a decreased count is still reported, not silently treated as no change`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        probes[0] = 5
        registry.advanceBaseline(registry.computeDeltaBatch(resource))

        // Only reachable in practice via a changed-layout array swap (see "a changed layout hash
        // allocates a fresh array" above); simulated directly here since that path is not
        // reachable through this v1 static-attach agent's own register() calls.
        probes[0] = 2

        val batch = registry.computeDeltaBatch(resource).batch

        assertEquals(2L, batch.deltas.single().hitsTotal)
    }

    @Test
    fun `after a reported decrease, an unchanged value is omitted again on the next flush`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        probes[0] = 5
        registry.advanceBaseline(registry.computeDeltaBatch(resource))
        probes[0] = 2
        registry.advanceBaseline(registry.computeDeltaBatch(resource))

        val batch = registry.computeDeltaBatch(resource).batch

        assertTrue(batch.deltas.isEmpty())
    }

    @Test
    fun `firstSeenAt is stamped once and stays stable across subsequent flushes`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        probes[0] += 1

        val firstSnapshot = registry.computeDeltaBatch(resource)
        val firstSeenAt =
            firstSnapshot.batch.deltas
                .single()
                .firstSeenAt
        registry.advanceBaseline(firstSnapshot)

        probes[0] += 1
        val secondBatch = registry.computeDeltaBatch(resource).batch

        assertEquals(firstSeenAt, secondBatch.deltas.single().firstSeenAt)
    }

    @Test
    fun `classId is assigned once per class and stays stable across re-registration`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        registry.register("com.example.Bar", layoutHash = 1L, probes = methodProbes(1))

        val fooProbes = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        fooProbes[0]++
        val delta =
            registry
                .computeDeltaBatch(resource)
                .batch.deltas
                .single()

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

        val manifest = registry.manifest(serviceName = "checkout", serviceVersion = "1.0.0", serviceInstanceId = "instance-1")

        val location = manifest.probes.single()
        assertEquals("com.example.Foo", location.className)
        assertEquals("bar", location.methodName)
        assertEquals(10, location.line)
        assertEquals(ProbeKind.METHOD, location.kind)
    }

    @Test
    fun `manifest and computeManifestDelta carry the service instance id`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))

        val manifest = registry.manifest(serviceName = "checkout", serviceVersion = null, serviceInstanceId = "instance-1")
        val delta =
            registry
                .computeManifestDelta(
                    serviceName = "checkout",
                    serviceVersion = null,
                    serviceInstanceId = "instance-1",
                ).manifest

        assertEquals(
            "instance-1",
            manifest.serviceInstanceId,
            "class_id is assigned independently per instance, so a collector needs an instance to key on",
        )
        assertEquals("instance-1", delta.serviceInstanceId)
    }

    @Test
    fun `computeManifestDelta reports probes not yet included in a sent manifest`() {
        val registry = ProbeRegistry()
        registry.register(
            "com.example.Foo",
            layoutHash = 1L,
            probes = listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", line = 10)),
        )

        val delta =
            registry
                .computeManifestDelta(
                    serviceName = "checkout",
                    serviceVersion = "1.0.0",
                    serviceInstanceId = "instance-1",
                ).manifest

        assertEquals("com.example.Foo", delta.probes.single().className)
    }

    @Test
    fun `advanceManifestBaseline marks reported classes so they are not sent again`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))

        registry.advanceManifestBaseline(
            registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null, serviceInstanceId = "instance-1"),
        )
        val secondDelta =
            registry
                .computeManifestDelta(
                    serviceName = "checkout",
                    serviceVersion = null,
                    serviceInstanceId = "instance-1",
                ).manifest

        assertTrue(secondDelta.probes.isEmpty())
    }

    @Test
    fun `a class registered after the manifest baseline advances appears in the next delta`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        registry.advanceManifestBaseline(
            registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null, serviceInstanceId = "instance-1"),
        )

        registry.register("com.example.Bar", layoutHash = 1L, probes = methodProbes(1))
        val secondDelta =
            registry
                .computeManifestDelta(
                    serviceName = "checkout",
                    serviceVersion = null,
                    serviceInstanceId = "instance-1",
                ).manifest

        assertEquals("com.example.Bar", secondDelta.probes.single().className)
    }

    @Test
    fun `a failed manifest send is not advanced, so the next computeManifestDelta retries the same classes`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))

        registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null, serviceInstanceId = "instance-1")
        // advanceManifestBaseline is never called here, simulating a failed send.
        val retryDelta =
            registry
                .computeManifestDelta(
                    serviceName = "checkout",
                    serviceVersion = null,
                    serviceInstanceId = "instance-1",
                ).manifest

        assertEquals("com.example.Foo", retryDelta.probes.single().className)
    }

    @Test
    fun `unregister removes a class so it no longer appears in the manifest`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))

        registry.unregister("com.example.Foo")

        assertTrue(registry.manifest(serviceName = "checkout", serviceVersion = null, serviceInstanceId = "instance-1").probes.isEmpty())
    }

    @Test
    fun `unregister only removes the named class, leaving others intact`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        registry.register("com.example.Bar", layoutHash = 1L, probes = methodProbes(1))

        registry.unregister("com.example.Foo")

        assertEquals(
            "com.example.Bar",
            registry
                .manifest(serviceName = "checkout", serviceVersion = null, serviceInstanceId = "instance-1")
                .probes
                .single()
                .className,
        )
    }

    @Test
    fun `recordSkipped adds a class to the manifest with no probes`() {
        val registry = ProbeRegistry()

        registry.recordSkipped("com.example.Foo", reason = "annotation not supported on TYPE")

        val manifest = registry.manifest(serviceName = "checkout", serviceVersion = null, serviceInstanceId = "instance-1")
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

        val skipped =
            registry
                .manifest(
                    serviceName = "checkout",
                    serviceVersion = null,
                    serviceInstanceId = "instance-1",
                ).skippedClasses
                .single()
        assertEquals("first reason", skipped.reason)
    }

    @Test
    fun `computeManifestDelta reports skipped classes not yet included in a sent manifest`() {
        val registry = ProbeRegistry()
        registry.recordSkipped("com.example.Foo", reason = "annotation not supported on TYPE")

        val delta =
            registry
                .computeManifestDelta(
                    serviceName = "checkout",
                    serviceVersion = null,
                    serviceInstanceId = "instance-1",
                ).manifest

        assertEquals("com.example.Foo", delta.skippedClasses.single().className)
    }

    @Test
    fun `advanceManifestBaseline marks a skipped class as sent so it is not repeated`() {
        val registry = ProbeRegistry()
        registry.recordSkipped("com.example.Foo", reason = "annotation not supported on TYPE")

        registry.advanceManifestBaseline(
            registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null, serviceInstanceId = "instance-1"),
        )
        val secondDelta =
            registry
                .computeManifestDelta(
                    serviceName = "checkout",
                    serviceVersion = null,
                    serviceInstanceId = "instance-1",
                ).manifest

        assertTrue(secondDelta.skippedClasses.isEmpty())
    }

    @Test
    fun `a failed manifest send is not advanced, so the next computeManifestDelta retries the same skipped class`() {
        val registry = ProbeRegistry()
        registry.recordSkipped("com.example.Foo", reason = "annotation not supported on TYPE")

        registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null, serviceInstanceId = "instance-1")
        // advanceManifestBaseline is never called here, simulating a failed send.
        val retryDelta =
            registry
                .computeManifestDelta(
                    serviceName = "checkout",
                    serviceVersion = null,
                    serviceInstanceId = "instance-1",
                ).manifest

        assertEquals("com.example.Foo", retryDelta.skippedClasses.single().className)
    }

    @Test
    fun `advancing an older delta snapshot after a newer one does not roll the last-sent value back`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        probes[0] = 3
        val older = registry.computeDeltaBatch(resource)
        probes[0] = 5
        val newer = registry.computeDeltaBatch(resource)

        // The newer send is confirmed first, then the older one lands (e.g. a scheduled flush and
        // the shutdown flush racing each other).
        registry.advanceBaseline(newer)
        registry.advanceBaseline(older)

        assertTrue(
            registry
                .computeDeltaBatch(resource)
                .batch.deltas
                .isEmpty(),
            "5 was already delivered; 3 must not win",
        )
    }

    @Test
    fun `advancing only the older of two delta snapshots leaves the newer hits pending`() {
        val registry = ProbeRegistry()
        val probes = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        probes[0] = 3
        val older = registry.computeDeltaBatch(resource)
        probes[0] = 5
        registry.computeDeltaBatch(resource) // the newer send fails, so it is never advanced

        registry.advanceBaseline(older)

        assertEquals(
            5L,
            registry
                .computeDeltaBatch(resource)
                .batch.deltas
                .single()
                .hitsTotal,
        )
    }

    @Test
    fun `advancing a manifest snapshot only marks the classes that snapshot staged`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        val first = registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null, serviceInstanceId = "instance-1")
        registry.register("com.example.Bar", layoutHash = 1L, probes = methodProbes(1))
        registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null, serviceInstanceId = "instance-1")

        // Only the first (Foo-only) send is confirmed; the second one, which also carried Bar, failed.
        registry.advanceManifestBaseline(first)

        val retry =
            registry
                .computeManifestDelta(
                    serviceName = "checkout",
                    serviceVersion = null,
                    serviceInstanceId = "instance-1",
                ).manifest
        assertEquals(listOf("com.example.Bar"), retry.probes.map { it.className })
    }

    @Test
    fun `advancing a manifest snapshot only marks the skipped classes that snapshot staged`() {
        val registry = ProbeRegistry()
        registry.recordSkipped("com.example.Foo", reason = "first")
        val first = registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null, serviceInstanceId = "instance-1")
        registry.recordSkipped("com.example.Bar", reason = "second")
        registry.computeManifestDelta(serviceName = "checkout", serviceVersion = null, serviceInstanceId = "instance-1")

        registry.advanceManifestBaseline(first)

        val retry =
            registry
                .computeManifestDelta(
                    serviceName = "checkout",
                    serviceVersion = null,
                    serviceInstanceId = "instance-1",
                ).manifest
        assertEquals(listOf("com.example.Bar"), retry.skippedClasses.map { it.className })
    }
}
