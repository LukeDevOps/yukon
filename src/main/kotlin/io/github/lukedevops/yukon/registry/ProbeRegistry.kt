package io.github.lukedevops.yukon.registry

import io.github.lukedevops.yukon.export.DeltaBatch
import io.github.lukedevops.yukon.export.ProbeDelta
import io.github.lukedevops.yukon.export.ProbeLocation
import io.github.lukedevops.yukon.export.ProbeManifest
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.export.SkippedClass
import java.lang.System.Logger.Level
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stores one probe-count array per class. Each array is a plain `long[]`,
 * keyed by (class name, probe-layout hash, defining classloader). A probe
 * hit does one direct `arr[index]++`, with no shared map and no atomic
 * operations on the hot path.
 *
 * This type only manages bookkeeping for those arrays: allocation,
 * baseline/delta accounting, and manifest metadata. Instrumented bytecode
 * receives the array at class-init, and writes to it directly from there.
 *
 * The key includes the probe-layout hash, not just the class name. This
 * matters for a class reloaded by the *same* classloader identity with an
 * unchanged layout (not reachable in this v1 static-attach agent today, but
 * cheap to keep correct for later). If the layout is unchanged, the class
 * keeps its existing array and history. If the layout changed, old counts
 * would not mean anything against the new bytecode, so the class gets a
 * fresh array instead of a merge.
 *
 * The key also includes the defining classloader's identity, not just the
 * class name. JVM class identity is (classloader, name), not name alone: two
 * different, concurrently active classloaders can define a class with the
 * same fully-qualified name (multi-tenant app servers, OSGi, plugin hosts),
 * and those are two unrelated classes that happen to share a name, not one
 * class reloaded. Keying on name alone would silently merge their hit
 * counts into one manifest entry. Identity is tracked via
 * [System.identityHashCode] rather than holding the [ClassLoader] itself, so
 * a retired classloader (for example, a devtools-style same-JVM reload that
 * swaps in a new classloader for changed classes) can still be garbage
 * collected instead of being pinned forever by this registry.
 *
 * This registry does not, on its own, give a class continuity across an
 * actual app/JVM restart: a fresh [ProbeRegistry] is created every time
 * [io.github.lukedevops.yukon.Agent.premain] runs, so restarting the process
 * always starts every count at zero regardless of this key. Long-running,
 * cross-restart visibility is the collector's job: it aggregates deltas
 * from every `service.instance.id` a service has ever reported, over time,
 * as described in the design notes for the export payloads.
 */
open class ProbeRegistry {
    private val log = System.getLogger(ProbeRegistry::class.java.name)

    private data class RegistryKey(
        val className: String,
        val layoutHash: Long,
        val classLoaderId: Int,
    )

    private class ClassEntry(
        val classId: Int,
        val className: String,
        val probes: List<ProbeMeta>,
        val counts: LongArray,
    ) {
        /** The last cumulative count successfully delivered to the collector, per probe. */
        var lastSent: LongArray = LongArray(counts.size)
        var pendingLastSent: LongArray? = null
        val firstSeenAt: LongArray = LongArray(counts.size)

        /** Tracks which probes have already logged the one-time decrease warning. */
        val decreaseWarned: BooleanArray = BooleanArray(counts.size)
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
     * in this class increments. A repeat call for an unchanged (className,
     * layoutHash, classLoader) returns the same array instance.
     */
    fun register(
        className: String,
        layoutHash: Long,
        probes: List<ProbeMeta>,
        classLoader: ClassLoader? = null,
    ): LongArray {
        val key = RegistryKey(className, layoutHash, System.identityHashCode(classLoader))
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
     * Removes the entry registered for [className] by this specific [classLoader], if any.
     *
     * Use this for a class whose instrumentation was registered speculatively, but then failed
     * to actually weave (for example, bytecode ByteBuddy refuses to redefine). It keeps that
     * class out of every future manifest. Without it, the class's probes would be permanently
     * reported as "never hit", when they can in fact never fire at all.
     *
     * Scoped to one classloader, not every entry sharing [className]: a different classloader's
     * class of the same name is a different class (see the class-level doc), and may already be
     * successfully instrumented. Removing it too, just because another loader's copy of the same
     * name failed, would be its own instance of the exact bug this method exists to prevent.
     */
    fun unregister(
        className: String,
        classLoader: ClassLoader? = null,
    ) {
        val classLoaderId = System.identityHashCode(classLoader)
        entriesByKey.keys.removeIf { it.className == className && it.classLoaderId == classLoaderId }
    }

    /**
     * Records a class the agent matched but could not instrument. This makes it visible on the
     * wire, not just in an agent-local log line. The class never gets a `classId` or any probes,
     * because it never reaches [register].
     *
     * Idempotent per class name. A repeat call, for example the same class loaded by a second
     * classloader, keeps the first reason and timestamp. It does not overwrite them.
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
     * Compares current counts against each entry's last successfully sent value, and returns
     * only the probes whose count changed.
     *
     * Each [ProbeDelta] carries the current cumulative count (`hits_total`), not the amount it
     * changed by. A collector merges cumulative counts with max(), so a batch that arrives twice
     * or out of order cannot double-count. This differs from an operation like "add 5 hits":
     * applying that twice, or out of order against a concurrent update, corrupts the total.
     *
     * The snapshot is stashed as a pending last-sent value, not applied immediately.
     * [advanceBaseline] must be called explicitly to apply it, and only after the batch is
     * confirmed delivered.
     */
    open fun computeDeltaBatch(resource: ResourceAttributes): DeltaBatch {
        val deltas = mutableListOf<ProbeDelta>()
        for (entry in entriesByKey.values) {
            val snapshot = entry.counts.copyOf()
            entry.pendingLastSent = snapshot
            for (index in snapshot.indices) {
                val current = snapshot[index]
                val lastSent = entry.lastSent[index]
                if (current == lastSent) continue
                if (current < lastSent) {
                    warnOnceAboutDecrease(entry, index, lastSent, current)
                }
                if (current > 0 && entry.firstSeenAt[index] == 0L) {
                    entry.firstSeenAt[index] = System.currentTimeMillis()
                }
                deltas +=
                    ProbeDelta(
                        classId = entry.classId,
                        probeIndex = index,
                        kind = entry.probes[index].kind,
                        firstSeenAt = entry.firstSeenAt[index],
                        hitsTotal = current,
                    )
            }
        }
        return DeltaBatch(resource, deltas)
    }

    /**
     * Logs a one-time warning the first time a probe's count is seen to drop. This should never
     * happen through this v1 static-attach agent's own [register] calls: the only path that
     * allocates a new, lower-starting array is a changed probe layout hash, and static attach
     * never retransforms an already-loaded class. Logging instead of silently sending the lower
     * value anyway means a future bug that does trigger this is visible, not hidden the way the
     * `@JvmName` transform failure was before it had its own explicit check.
     */
    private fun warnOnceAboutDecrease(
        entry: ClassEntry,
        index: Int,
        lastSent: Long,
        current: Long,
    ) {
        if (entry.decreaseWarned[index]) return
        entry.decreaseWarned[index] = true
        log.log(
            Level.WARNING,
            "yukon: probe count went backward for ${entry.className}#$index " +
                "(was $lastSent, now $current); reporting it as-is",
        )
    }

    /**
     * Advances every entry's last-sent value to its last-computed snapshot. It never advances to
     * the live counts: those may have moved further ahead, for example while a POST was in
     * flight.
     *
     * Call this only after a flush is confirmed delivered. A failed flush must leave the
     * last-sent value untouched, so the next attempt naturally reports the live count again.
     */
    fun advanceBaseline() {
        for (entry in entriesByKey.values) {
            entry.pendingLastSent?.let { entry.lastSent = it }
        }
    }

    /**
     * Sent once per (service, version) so the collector can resolve probe IDs to source.
     *
     * [serviceInstanceId] is carried on every manifest even though this method is otherwise
     * scoped to (service, version): `class_id` is assigned independently by each instance's own
     * registry, so a collector needs an instance to key on to avoid conflating two instances'
     * unrelated classes that happen to share a `class_id`. See the class-level doc on
     * [io.github.lukedevops.yukon.export.ProbeManifest].
     */
    fun manifest(
        serviceName: String,
        serviceVersion: String?,
        serviceInstanceId: String,
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
        return ProbeManifest(serviceName, serviceVersion, locations, skipped, serviceInstanceId)
    }

    /**
     * Returns only the probe locations for classes not yet included in a successfully sent
     * manifest. It stages them the same way [computeDeltaBatch] stages counts:
     * [advanceManifestBaseline] must be called explicitly, and only once the manifest is
     * confirmed delivered.
     *
     * A class that registers after an earlier successful send is picked up on a later call, not
     * left out of every manifest for the rest of the process's life.
     */
    open fun computeManifestDelta(
        serviceName: String,
        serviceVersion: String?,
        serviceInstanceId: String,
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
        return ProbeManifest(serviceName, serviceVersion, locations, skipped, serviceInstanceId)
    }

    /**
     * Marks every class staged by the last [computeManifestDelta] call as included, so it is
     * not sent again.
     *
     * Call this only after that manifest is confirmed delivered. A failed send must leave
     * entries unmarked, so the next attempt's delta naturally includes them again.
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
