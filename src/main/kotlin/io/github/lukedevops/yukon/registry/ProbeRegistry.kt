package io.github.lukedevops.yukon.registry

import io.github.lukedevops.yukon.export.DeltaBatch
import io.github.lukedevops.yukon.export.ProbeDelta
import io.github.lukedevops.yukon.export.ProbeLocation
import io.github.lukedevops.yukon.export.ProbeManifest
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.export.SkippedClass
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A dense, per-class probe array: one non-atomic `long[]` per (class,
 * probe-layout) key, one direct `arr[index]++` per probe hit, no shared map
 * on the hot path. This type only owns bookkeeping around those arrays
 * (allocation, baseline/delta accounting, manifest metadata). The array
 * itself is handed to instrumented bytecode at class-init and written to
 * directly from there.
 *
 * Keying by (class name, probe-layout hash) rather than class name alone
 * means a retransform with an unchanged layout keeps its history, while an
 * actual code change (different probe layout) gets a fresh array instead of
 * merging counts that no longer mean anything against new bytecode.
 */
class ProbeRegistry {
    private data class RegistryKey(
        val className: String,
        val layoutHash: Long,
    )

    private class ClassEntry(
        val classId: Int,
        val className: String,
        val probes: List<ProbeMeta>,
        val counts: LongArray,
    ) {
        var baseline: LongArray = LongArray(counts.size)
        var pendingBaseline: LongArray? = null
        val firstSeenAt: LongArray = LongArray(counts.size)
        var manifestIncluded: Boolean = false
        var pendingManifestInclusion: Boolean = false
    }

    private class SkippedEntry(
        val reason: String,
        val skippedAt: Long,
    ) {
        var manifestIncluded: Boolean = false
        var pendingManifestInclusion: Boolean = false
    }

    private val entriesByKey = ConcurrentHashMap<RegistryKey, ClassEntry>()
    private val skippedByClassName = ConcurrentHashMap<String, SkippedEntry>()
    private val nextClassId = AtomicInteger(0)

    /**
     * Called once per class transform. Returns the backing array every probe
     * in this class increments; repeat calls for an unchanged (className,
     * layoutHash) return the same array instance.
     */
    fun register(
        className: String,
        layoutHash: Long,
        probes: List<ProbeMeta>,
    ): LongArray {
        val key = RegistryKey(className, layoutHash)
        val entry =
            entriesByKey.computeIfAbsent(key) {
                ClassEntry(
                    classId = nextClassId.getAndIncrement(),
                    className = className,
                    probes = probes,
                    counts = LongArray(probes.size),
                )
            }
        return entry.counts
    }

    /**
     * Removes every entry registered for [className], regardless of layout hash.
     * For a class whose instrumentation was registered speculatively but then
     * failed to actually weave (e.g. bytecode ByteBuddy refuses to redefine),
     * this keeps it out of every future manifest instead of permanently
     * reporting probes that can never fire as "never hit".
     */
    fun unregister(className: String) {
        entriesByKey.keys.removeIf { it.className == className }
    }

    /**
     * Records a class the agent matched but could not instrument, so it's
     * visible on the wire instead of only in an agent-local log line. Never
     * gets a `classId` or any probes, since it never reaches [register].
     * Idempotent per class name: a repeat call (e.g. the same class loaded
     * by a second classloader) keeps the first reason and timestamp rather
     * than overwriting them.
     */
    fun recordSkipped(
        className: String,
        reason: String,
    ) {
        skippedByClassName.computeIfAbsent(className) {
            SkippedEntry(reason, System.currentTimeMillis())
        }
    }

    /**
     * Snapshots current counts against each entry's baseline and returns only
     * what changed. The snapshot is stashed as a pending baseline rather than
     * applied immediately: [advanceBaseline] must be called explicitly, and
     * only after the batch is confirmed delivered.
     */
    fun computeDeltaBatch(resource: ResourceAttributes): DeltaBatch {
        val deltas = mutableListOf<ProbeDelta>()
        for (entry in entriesByKey.values) {
            val snapshot = entry.counts.copyOf()
            entry.pendingBaseline = snapshot
            for (index in snapshot.indices) {
                val delta = snapshot[index] - entry.baseline[index]
                if (delta <= 0) continue
                if (entry.firstSeenAt[index] == 0L) {
                    entry.firstSeenAt[index] = System.currentTimeMillis()
                }
                deltas +=
                    ProbeDelta(
                        classId = entry.classId,
                        probeIndex = index,
                        kind = entry.probes[index].kind,
                        firstSeenAt = entry.firstSeenAt[index],
                        hitsSinceLastFlush = delta,
                    )
            }
        }
        return DeltaBatch(resource, deltas)
    }

    /**
     * Advances every entry's baseline to its last-computed snapshot, never to
     * the live counts, which may have moved further ahead (e.g. while a POST
     * was in flight). Call only after a flush is confirmed delivered; a failed
     * flush must leave the baseline untouched so the next attempt's delta
     * naturally includes everything accrued since the last success.
     */
    fun advanceBaseline() {
        for (entry in entriesByKey.values) {
            entry.pendingBaseline?.let { entry.baseline = it }
        }
    }

    /** Sent once per (service, version) so the collector can resolve probe IDs to source. */
    fun manifest(
        serviceName: String,
        serviceVersion: String?,
    ): ProbeManifest {
        val locations =
            entriesByKey.values.flatMap { entry ->
                entry.probes.mapIndexed { index, meta ->
                    ProbeLocation(
                        classId = entry.classId,
                        probeIndex = index,
                        kind = meta.kind,
                        className = entry.className,
                        methodName = meta.methodName,
                        methodDescriptor = meta.methodDescriptor,
                        line = meta.line,
                        branchIndex = meta.branchIndex,
                    )
                }
            }
        val skipped =
            skippedByClassName.map { (className, entry) ->
                SkippedClass(className, entry.reason, entry.skippedAt)
            }
        return ProbeManifest(serviceName, serviceVersion, locations, skipped)
    }

    /**
     * Returns only the probe locations for classes not yet included in a
     * successfully sent manifest, staged the same way [computeDeltaBatch]
     * stages counts: [advanceManifestBaseline] must be called explicitly,
     * and only once the manifest is confirmed delivered. A class that
     * registers after an earlier successful send is picked up here instead
     * of being left out of every manifest for the rest of the process's
     * life.
     */
    fun computeManifestDelta(
        serviceName: String,
        serviceVersion: String?,
    ): ProbeManifest {
        val locations = mutableListOf<ProbeLocation>()
        for (entry in entriesByKey.values) {
            entry.pendingManifestInclusion = !entry.manifestIncluded
            if (!entry.pendingManifestInclusion) continue
            entry.probes.forEachIndexed { index, meta ->
                locations +=
                    ProbeLocation(
                        classId = entry.classId,
                        probeIndex = index,
                        kind = meta.kind,
                        className = entry.className,
                        methodName = meta.methodName,
                        methodDescriptor = meta.methodDescriptor,
                        line = meta.line,
                        branchIndex = meta.branchIndex,
                    )
            }
        }
        val skipped = mutableListOf<SkippedClass>()
        for ((className, entry) in skippedByClassName) {
            entry.pendingManifestInclusion = !entry.manifestIncluded
            if (!entry.pendingManifestInclusion) continue
            skipped += SkippedClass(className, entry.reason, entry.skippedAt)
        }
        return ProbeManifest(serviceName, serviceVersion, locations, skipped)
    }

    /**
     * Marks every class staged by the last [computeManifestDelta] call as
     * included, so it isn't sent again. Call only after that manifest is
     * confirmed delivered; a failed send must leave entries unmarked so the
     * next attempt's delta naturally includes them again.
     */
    fun advanceManifestBaseline() {
        for (entry in entriesByKey.values) {
            if (entry.pendingManifestInclusion) entry.manifestIncluded = true
        }
        for (entry in skippedByClassName.values) {
            if (entry.pendingManifestInclusion) entry.manifestIncluded = true
        }
    }
}
