package io.github.lukedevops.yukon.registry

import io.github.lukedevops.yukon.export.CallEdge
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ResourceAttributes
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `lookup returns the registered array for the exact key and null otherwise`() {
        val registry = ProbeRegistry()
        val loader = URLClassLoader(emptyArray())
        val registered = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(2), classLoader = loader)

        assertSame(registered, registry.lookup("com.example.Foo", 1L, loader))
        assertEquals(null, registry.lookup("com.example.Foo", 2L, loader), "a different layout is a different array")
        assertEquals(null, registry.lookup("com.example.Foo", 1L, URLClassLoader(emptyArray())), "a different loader too")
        assertEquals(null, registry.lookup("com.example.Bar", 1L, loader))
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
        assertFalse(location.inline)
    }

    @Test
    fun `manifest and computeManifestDelta both carry the inline flag`() {
        val registry = ProbeRegistry()
        registry.register(
            "com.example.Foo",
            layoutHash = 1L,
            probes = listOf(ProbeMeta(ProbeKind.METHOD, "bar", "()V", line = 10, inline = true)),
        )

        val manifestLocation = registry.manifest("checkout", "1.0.0", "instance-1").probes.single()
        val deltaLocation =
            registry
                .computeManifestDelta("checkout", "1.0.0", "instance-1")
                .manifest.probes
                .single()

        assertTrue(manifestLocation.inline)
        assertTrue(deltaLocation.inline)
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
    fun `computeDeltaBatches packs whole classes into chunks up to the cap`() {
        val registry = ProbeRegistry()
        val foo = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(3))
        val bar = registry.register("com.example.Bar", layoutHash = 1L, probes = methodProbes(3))
        val baz = registry.register("com.example.Baz", layoutHash = 1L, probes = methodProbes(3))
        for (array in listOf(foo, bar, baz)) for (i in array.indices) array[i] = 1

        val chunks = registry.computeDeltaBatches(resource, maxDeltasPerBatch = 4)

        // 9 changed probes, 3 per class, cap 4: a class is never split across chunks, so each
        // chunk carries exactly one class rather than filling to 4.
        assertEquals(listOf(3, 3, 3), chunks.map { it.batch.deltas.size })
        assertEquals(3, chunks.flatMap { chunk -> chunk.batch.deltas.map { it.classId } }.toSet().size)
        chunks.forEach { chunk ->
            assertEquals(
                1,
                chunk.batch.deltas
                    .map { it.classId }
                    .toSet()
                    .size,
            )
        }
    }

    @Test
    fun `a class whose changed probes alone exceed the cap gets its own oversized chunk`() {
        val registry = ProbeRegistry()
        val big = registry.register("com.example.Big", layoutHash = 1L, probes = methodProbes(10))
        for (i in big.indices) big[i] = 1

        val chunks = registry.computeDeltaBatches(resource, maxDeltasPerBatch = 4)

        assertEquals(1, chunks.size)
        assertEquals(
            10,
            chunks
                .single()
                .batch.deltas.size,
        )
    }

    @Test
    fun `computeDeltaBatches with nothing changed still returns one empty batch for the heartbeat`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(3))

        val chunks = registry.computeDeltaBatches(resource, maxDeltasPerBatch = 4)

        assertEquals(1, chunks.size)
        assertTrue(
            chunks
                .single()
                .batch.deltas
                .isEmpty(),
        )
    }

    @Test
    fun `advancing one delta chunk leaves the other chunks' classes pending`() {
        val registry = ProbeRegistry()
        val foo = registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(2))
        val bar = registry.register("com.example.Bar", layoutHash = 1L, probes = methodProbes(2))
        foo[0] = 1
        bar[0] = 1
        val chunks = registry.computeDeltaBatches(resource, maxDeltasPerBatch = 1)
        assertEquals(2, chunks.size)

        registry.advanceBaseline(chunks[0])

        val pending = registry.computeDeltaBatch(resource).batch.deltas
        assertEquals(1, pending.size)
        assertEquals(
            chunks[1]
                .batch.deltas
                .single()
                .classId,
            pending.single().classId,
        )
    }

    @Test
    fun `computeManifestDeltas packs whole classes and counts skipped classes toward the cap`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(3))
        registry.register("com.example.Bar", layoutHash = 1L, probes = methodProbes(3))
        registry.recordSkipped("com.example.Skipped", reason = "unsafe")

        val chunks = registry.computeManifestDeltas("checkout", null, "instance-1", maxEntriesPerChunk = 5)

        // Each of Foo and Bar weighs 3 probes + 0 edges + 1 for its own supertypes record = 4.
        // Foo (4) fills the first chunk on its own, since Bar (4) would push it past 5. Bar and
        // the skipped class (1) then fit together in the second, exactly at the cap.
        assertEquals(2, chunks.size)
        assertEquals(
            setOf("com.example.Foo", "com.example.Bar"),
            chunks.flatMap { it.manifest.probes.map { p -> p.className } }.toSet(),
        )
        assertEquals(listOf("com.example.Skipped"), chunks.flatMap { it.manifest.skippedClasses.map { s -> s.className } })
        chunks.forEach {
            assertTrue(it.manifest.probes.size + it.manifest.skippedClasses.size + it.manifest.classSupertypes.size <= 5)
        }
    }

    @Test
    fun `computeManifestDeltas returns no chunks when there is nothing to send`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        registry.advanceManifestBaseline(registry.computeManifestDelta("checkout", null, "instance-1"))

        assertTrue(registry.computeManifestDeltas("checkout", null, "instance-1", maxEntriesPerChunk = 4).isEmpty())
    }

    @Test
    fun `advancing one manifest chunk leaves the other chunks' classes pending`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))
        registry.register("com.example.Bar", layoutHash = 1L, probes = methodProbes(1))
        val chunks = registry.computeManifestDeltas("checkout", null, "instance-1", maxEntriesPerChunk = 1)
        assertEquals(2, chunks.size)

        registry.advanceManifestBaseline(chunks[0])

        val pending = registry.computeManifestDelta("checkout", null, "instance-1").manifest.probes
        assertEquals(
            chunks[1]
                .manifest.probes
                .single()
                .className,
            pending.single().className,
        )
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

    @Test
    fun `manifest and computeManifestDelta carry an optional argument probe's parameter fields`() {
        val registry = ProbeRegistry()
        registry.register(
            "com.example.Foo",
            layoutHash = 1L,
            probes =
                listOf(
                    ProbeMeta(
                        ProbeKind.OPTIONAL_ARGUMENT,
                        "bar",
                        "(I)V",
                        line = 10,
                        parameterIndex = 0,
                        parameterName = "count",
                        overridable = true,
                    ),
                ),
        )

        val manifestLocation = registry.manifest("checkout", "1.0.0", "instance-1").probes.single()
        val deltaLocation =
            registry
                .computeManifestDelta("checkout", "1.0.0", "instance-1")
                .manifest.probes
                .single()

        for (location in listOf(manifestLocation, deltaLocation)) {
            assertEquals(ProbeKind.OPTIONAL_ARGUMENT, location.kind)
            assertEquals(0, location.parameterIndex)
            assertEquals("count", location.parameterName)
            assertTrue(location.overridable)
        }
    }

    @Test
    fun `manifest and computeManifestDelta carry a METHOD probe's call edges and the class's supertypes record`() {
        val registry = ProbeRegistry()
        val calls = listOf(CallEdge("com.example.Bar", "baz", "()V", virtual = true))
        registry.register(
            "com.example.Foo",
            layoutHash = 1L,
            probes = listOf(ProbeMeta(ProbeKind.METHOD, "run", "()V", line = 1, calls = calls)),
            superClassName = "com.example.Base",
            interfaceNames = listOf("com.example.Marker"),
        )

        val manifestLocation = registry.manifest("checkout", "1.0.0", "instance-1").probes.single()
        val manifestSupertypes = registry.manifest("checkout", "1.0.0", "instance-1").classSupertypes.single()
        val deltaSnapshot = registry.computeManifestDelta("checkout", "1.0.0", "instance-1")

        assertEquals(calls, manifestLocation.calls)
        assertEquals(
            calls,
            deltaSnapshot.manifest.probes
                .single()
                .calls,
        )
        assertEquals("com.example.Base", manifestSupertypes.superClassName)
        assertEquals(listOf("com.example.Marker"), manifestSupertypes.interfaceNames)
        assertEquals(manifestSupertypes, deltaSnapshot.manifest.classSupertypes.single())
    }

    @Test
    fun `the registry passes ProbeMeta calls through unchanged whatever the probe kind`() {
        val registry = ProbeRegistry()
        val calls = listOf(CallEdge("com.example.Bar", "baz", "()V", virtual = true))
        registry.register(
            "com.example.Foo",
            layoutHash = 1L,
            probes = listOf(ProbeMeta(ProbeKind.BRANCH, "run", "()V", line = 1, branchIndex = 0, calls = calls)),
        )

        // ProbeMeta.calls is populated only for METHOD-kind probes by YukonInstrumentation; the
        // registry itself carries through whatever it is given, so this pins that a manifest
        // location built from a BRANCH probe still reports whatever calls its ProbeMeta carried.
        // Real BRANCH probes never carry any: see CallEdgeInstrumentationTest.
        assertEquals(
            calls,
            registry
                .manifest("checkout", null, "instance-1")
                .probes
                .single()
                .calls,
        )
    }

    @Test
    fun `a class with many call edges seals a manifest chunk earlier than one without`() {
        val registry = ProbeRegistry()
        val manyCalls = (0 until 5).map { CallEdge("com.example.Callee", "m$it", "()V", virtual = false) }
        registry.register(
            "com.example.Heavy",
            layoutHash = 1L,
            probes = listOf(ProbeMeta(ProbeKind.METHOD, "run", "()V", line = 1, calls = manyCalls)),
        )
        registry.register("com.example.Light", layoutHash = 1L, probes = methodProbes(1))

        // Heavy weighs 1 probe + 5 edges + 1 supertypes record = 7, already past a cap of 6, so it
        // seals its own chunk; Light (1 + 0 + 1 = 2) starts a second chunk. entriesByKey is a
        // ConcurrentHashMap, so which chunk lands first is not guaranteed; only that the two
        // classes never land in the same chunk.
        val chunks = registry.computeManifestDeltas("checkout", null, "instance-1", maxEntriesPerChunk = 6)

        assertEquals(2, chunks.size)
        val classNamesPerChunk =
            chunks.map { chunk ->
                chunk.manifest.probes
                    .map { it.className }
                    .toSet()
            }
        assertEquals(listOf(setOf("com.example.Heavy"), setOf("com.example.Light")).toSet(), classNamesPerChunk.toSet())
    }

    @Test
    fun `advanceManifestBaseline marks a class's supertypes record as included together with its probes`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1), superClassName = "com.example.Base")

        val snapshot = registry.computeManifestDelta("checkout", null, "instance-1")
        assertEquals(1, snapshot.manifest.classSupertypes.size)
        registry.advanceManifestBaseline(snapshot)

        val retry = registry.computeManifestDelta("checkout", null, "instance-1")
        assertTrue(retry.manifest.probes.isEmpty())
        assertTrue(retry.manifest.classSupertypes.isEmpty())
    }

    @Test
    fun `re-registering the same class and layout hash keeps the first-registered supertypes`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1), superClassName = "com.example.First")

        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1), superClassName = "com.example.Second")

        // Supertypes play no part in the registry key, the same as the probe list itself: a
        // repeat call for an unchanged (className, layoutHash, classLoader) is a no-op, so the
        // layout hash a caller computes from methods and branches alone stays meaningful whether
        // or not calls or supertypes are attached. See ADR 0024.
        assertEquals(
            "com.example.First",
            registry
                .manifest("checkout", null, "instance-1")
                .classSupertypes
                .single()
                .superClassName,
        )
    }

    @Test
    fun `an unreported class goes out once and is not resent after a confirmed delivery`() {
        val registry = ProbeRegistry()
        assertTrue(registry.recordUnreported("com.example.Deflected"), "the first sighting is new")
        assertFalse(registry.recordUnreported("com.example.Deflected"), "a later sweep finds the same class again")

        val first = registry.computeManifestDelta("checkout", null, "instance-1")
        assertEquals(
            "com.example.Deflected",
            first.manifest.unreportedClasses
                .single()
                .className,
        )
        registry.advanceManifestBaseline(first)

        val second = registry.computeManifestDelta("checkout", null, "instance-1")
        assertTrue(second.manifest.unreportedClasses.isEmpty(), "a delivered class is not sent again")
    }

    @Test
    fun `an unreported class that failed to send is staged again on the next manifest`() {
        val registry = ProbeRegistry()
        registry.recordUnreported("com.example.Deflected")

        // The snapshot is computed and its send fails, so advanceManifestBaseline is never called.
        registry.computeManifestDelta("checkout", null, "instance-1")

        val retry = registry.computeManifestDelta("checkout", null, "instance-1")
        assertEquals(
            "com.example.Deflected",
            retry.manifest.unreportedClasses
                .single()
                .className,
        )
    }

    @Test
    fun `unaccountedFrom keeps only names the registry has never heard of`() {
        val registry = ProbeRegistry()
        registry.register("com.example.Registered", layoutHash = 1L, probes = methodProbes(1))
        registry.recordSkipped("com.example.Skipped", "unsafe annotation")
        registry.recordNothingToProbe("com.example.Empty")

        val unaccounted =
            registry.unaccountedFrom(
                listOf("com.example.Registered", "com.example.Skipped", "com.example.Empty", "com.example.Deflected"),
            )

        assertEquals(listOf("com.example.Deflected"), unaccounted)
    }

    @Test
    fun `an unreported class is dropped once another classloader registers the same name`() {
        val registry = ProbeRegistry()
        registry.recordUnreported("com.example.Foo")
        assertEquals(1, registry.unreportedClassCount())

        registry.register("com.example.Foo", layoutHash = 1L, probes = methodProbes(1))

        assertEquals(1, registry.purgeAccountedFor(), "the name is accounted for now")
        assertEquals(0, registry.unreportedClassCount())
        assertTrue(registry.manifest("checkout", null, "instance-1").unreportedClasses.isEmpty())
    }
}
